package com.auctiontracker.sale;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.Money;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRepository;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import com.auctiontracker.core.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Facade of the sale module: confirm-sale and mark-unsold (DESIGN.md 5.4 / 5.5).
 * All validation happens before the first mutation, and the write methods are
 * @Transactional — a failure partway through rolls back everything.
 */
@Service
public class SaleService {

    private static final String RECORDED_BY = "admin"; // real identity arrives with security (phase 5)

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final SaleRepository sales;
    private final FeasibilityService feasibility;
    private final AuctionLock lock;
    private final AuctionProperties props;
    private final BiddingService bidding;

    public SaleService(PlayerRepository players, TeamRepository teams, SaleRepository sales,
                       FeasibilityService feasibility, AuctionLock lock, AuctionProperties props,
                       BiddingService bidding) {
        this.players = players;
        this.teams = teams;
        this.sales = sales;
        this.feasibility = feasibility;
        this.lock = lock;
        this.props = props;
        this.bidding = bidding;
    }

    public record SaleResult(Player player, Team team) {}

    /** Commits whatever is currently the leading bid. */
    @Transactional
    public SaleResult confirmSale(UUID playerId) {
        synchronized (lock) {
            Player player = requirePlayer(playerId);

            if (player.getStatus() == PlayerStatus.SOLD) {
                Team buyer = teams.findById(player.getSoldToTeamId()).orElse(null);
                throw AuctionException.conflict("ALREADY_SOLD",
                        "%s was already sold to %s for %s".formatted(
                                player.getName(),
                                buyer != null ? buyer.getName() : "another team",
                                Money.inr(player.getSoldPrice())));
            }
            if (player.getStatus() != PlayerStatus.UNDER_AUCTION) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is %s — nothing to confirm".formatted(player.getName(), player.getStatus()));
            }
            BiddingService.LeadingBid leading = bidding.leadingBid(playerId).orElseThrow(() ->
                    AuctionException.conflict("NO_BIDS",
                            "No bids placed on %s yet — place a bid or mark unsold".formatted(player.getName())));

            Team team = teams.findById(leading.teamId()).orElseThrow(() ->
                    AuctionException.notFound("TEAM_NOT_FOUND",
                            "Leading team no longer exists: " + leading.teamId()));
            long price = leading.amount();

            // Defense in depth (DESIGN.md 5.4 rule 2): re-run the acquire checks
            // even though state shouldn't have changed since the last bid.
            feasibility.assertCanAcquire(team, player, price);

            // Atomic commit block — covered by this method's DB transaction.
            // The in-memory bid trail lands as BidEvent rows in the same commit.
            bidding.flushLiveBids(playerId);
            player.setStatus(PlayerStatus.SOLD);
            player.setSoldToTeamId(team.getTeamId());
            player.setSoldPrice(price);
            player.setSoldAt(Instant.now());
            players.save(player);

            team.setRemainingPurse(team.getRemainingPurse() - price);
            team.getSquadPlayerIds().add(player.getPlayerId());
            teams.save(team);

            sales.save(Sale.sold(player.getPlayerId(), player.getName(),
                    team.getTeamId(), team.getName(), price, RECORDED_BY));

            return new SaleResult(player, team);
        }
    }

    /**
     * Marks a player unsold — covers "nobody bid", "leading bidder walked away",
     * and "withdrawn before ever going under auction". No purse impact.
     *
     * Demotion rule ({@code auction.demote-unsold-players}): a player unsold
     * while UNDER_AUCTION drops one group (A→B→…→E), takes the lower group's
     * base price, and returns to the pool as AVAILABLE for re-auction. A
     * group-E player can't drop further but STILL returns to AVAILABLE — the
     * sell option for a lowest-group player never goes away. Withdrawing a
     * never-auctioned (AVAILABLE) player is always terminal UNSOLD.
     */
    @Transactional
    public Player markUnsold(UUID playerId) {
        synchronized (lock) {
            Player player = requirePlayer(playerId);
            if (player.getStatus() != PlayerStatus.AVAILABLE
                    && player.getStatus() != PlayerStatus.UNDER_AUCTION) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is %s — only AVAILABLE or UNDER_AUCTION players can be marked unsold"
                                .formatted(player.getName(), player.getStatus()));
            }
            boolean wasUnderAuction = player.getStatus() == PlayerStatus.UNDER_AUCTION;
            // Persist the live bid trail (if any) for the audit replay, then drop the session.
            bidding.flushLiveBids(playerId);

            if (wasUnderAuction && props.demoteUnsoldPlayers()) {
                PlayerCategory lowerGroup = player.getCategory().nextLower();
                if (lowerGroup != null) {
                    player.setCategory(lowerGroup);
                    player.setBasePrice(props.basePriceFor(lowerGroup));
                }
                // Demoted one group, or already at the lowest group — either way the
                // player goes back into the pool AVAILABLE so it can be put on the
                // block again. Group E is a sticky floor, never terminally unsold.
                player.setStatus(PlayerStatus.AVAILABLE);
            } else {
                // Withdrawn before ever going under auction (or demotion disabled).
                player.setStatus(PlayerStatus.UNSOLD);
            }
            players.save(player);

            sales.save(Sale.unsold(player.getPlayerId(), player.getName(), RECORDED_BY));
            return player;
        }
    }

    /**
     * Pre-auction retention (rules in {@code auction.retention}): the player
     * joins the team's squad for a flat group-based fee (RULE 2 — cost-group-a
     * for A, cost-other-groups for B–E), deducted from the purse. Caps:
     * max-per-team total, split between group A and the lower groups.
     */
    @Transactional
    public SaleResult retainPlayer(UUID teamId, UUID playerId) {
        synchronized (lock) {
            Player player = requirePlayer(playerId);
            Team team = teams.findById(teamId).orElseThrow(() ->
                    AuctionException.notFound("TEAM_NOT_FOUND", "No team with id " + teamId));
            if (player.getStatus() != PlayerStatus.AVAILABLE) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is %s — only AVAILABLE players can be retained"
                                .formatted(player.getName(), player.getStatus()));
            }

            AuctionProperties.Retention rules = props.retention();
            List<Player> retained = players.findBySoldToTeamId(teamId).stream()
                    .filter(p -> p.getStatus() == PlayerStatus.RETAINED)
                    .toList();
            if (retained.size() >= rules.maxPerTeam()) {
                throw AuctionException.conflict("RETENTION_LIMIT",
                        "%s has already retained %d player(s) — the maximum is %d"
                                .formatted(team.getName(), retained.size(), rules.maxPerTeam()));
            }
            boolean topGroup = player.getCategory() == PlayerCategory.A;
            long inSameBucket = retained.stream()
                    .filter(p -> (p.getCategory() == PlayerCategory.A) == topGroup)
                    .count();
            if (topGroup && inSameBucket >= rules.maxFromGroupA()) {
                throw AuctionException.conflict("RETENTION_LIMIT",
                        "%s has already retained %d group-A player(s) — the maximum is %d"
                                .formatted(team.getName(), inSameBucket, rules.maxFromGroupA()));
            }
            if (!topGroup && inSameBucket >= rules.maxFromLowerGroups()) {
                throw AuctionException.conflict("RETENTION_LIMIT",
                        "%s has already retained %d player(s) from the lower groups (B–E) — the maximum is %d"
                                .formatted(team.getName(), inSameBucket, rules.maxFromLowerGroups()));
            }

            // RULE 2: retention costs a flat fee by group (A vs. any lower group),
            // not the player's base price.
            long price = props.retentionCostFor(player.getCategory());
            // Same purse / squad-size / group-quota guards as buying at auction.
            feasibility.assertCanAcquire(team, player, price);

            player.setStatus(PlayerStatus.RETAINED);
            player.setSoldToTeamId(team.getTeamId());
            player.setSoldPrice(price);
            player.setSoldAt(Instant.now());
            players.save(player);

            team.setRemainingPurse(team.getRemainingPurse() - price);
            team.getSquadPlayerIds().add(player.getPlayerId());
            teams.save(team);

            sales.save(Sale.retained(player.getPlayerId(), player.getName(),
                    team.getTeamId(), team.getName(), price, RECORDED_BY));
            return new SaleResult(player, team);
        }
    }

    /** Undo a retention: player returns to the pool, purse refunded. */
    @Transactional
    public SaleResult releasePlayer(UUID playerId) {
        synchronized (lock) {
            Player player = requirePlayer(playerId);
            if (player.getStatus() != PlayerStatus.RETAINED) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is %s — only RETAINED players can be released"
                                .formatted(player.getName(), player.getStatus()));
            }
            Team team = teams.findById(player.getSoldToTeamId()).orElseThrow(() ->
                    AuctionException.notFound("TEAM_NOT_FOUND",
                            "Retaining team no longer exists: " + player.getSoldToTeamId()));
            long refund = player.getSoldPrice();

            player.setStatus(PlayerStatus.AVAILABLE);
            player.setSoldToTeamId(null);
            player.setSoldPrice(null);
            player.setSoldAt(null);
            players.save(player);

            team.setRemainingPurse(team.getRemainingPurse() + refund);
            team.getSquadPlayerIds().remove(player.getPlayerId());
            teams.save(team);

            sales.save(Sale.released(player.getPlayerId(), player.getName(),
                    team.getTeamId(), team.getName(), refund, RECORDED_BY));
            return new SaleResult(player, team);
        }
    }

    /** Setup-time wipe (players are being replaced); not used mid-auction. */
    @Transactional
    public void deleteAllSales() {
        sales.deleteAll();
    }

    /** Full chronological audit trail: every sale and every unsold decision. */
    public List<Sale> auditLog() {
        return sales.findAllByOrderByRecordedAtAsc();
    }

    private Player requirePlayer(UUID playerId) {
        return players.findById(playerId).orElseThrow(() ->
                AuctionException.notFound("PLAYER_NOT_FOUND", "No player with id " + playerId));
    }
}
