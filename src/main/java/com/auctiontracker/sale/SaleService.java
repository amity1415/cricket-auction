package com.auctiontracker.sale;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.tournament.RuleBook;
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
    private final RuleBook ruleBook;
    private final BiddingService bidding;

    public SaleService(PlayerRepository players, TeamRepository teams, SaleRepository sales,
                       FeasibilityService feasibility, AuctionLock lock, RuleBook ruleBook,
                       BiddingService bidding) {
        this.players = players;
        this.teams = teams;
        this.sales = sales;
        this.feasibility = feasibility;
        this.lock = lock;
        this.ruleBook = ruleBook;
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

            AuctionProperties rules = ruleBook.current();
            AuctionProperties.GroupTransition transition =
                    wasUnderAuction ? rules.unsoldTransitionFor(player.getCategory()) : null;
            if (transition != null) {
                // Config-driven cascade (e.g. role-based format): move the player to
                // the configured destination group and re-price them to that group's
                // own base price (so a transferred player always starts at the base
                // of the pool they land in), unless the transition sets an explicit
                // override. Then back to the pool AVAILABLE to be re-auctioned there.
                // A group with no transition entry is terminal → falls through below.
                player.setCategory(transition.destination());
                Long override = transition.destinationBasePrice();
                player.setBasePrice(override != null ? override
                        : rules.basePriceFor(transition.destination()));
                player.setStatus(PlayerStatus.AVAILABLE);
            } else if (wasUnderAuction && rules.demoteUnsoldPlayers()) {
                PlayerCategory lowerGroup = player.getCategory().nextLower();
                if (lowerGroup != null) {
                    player.setCategory(lowerGroup);
                    player.setBasePrice(rules.basePriceFor(lowerGroup));
                }
                // Demoted one group, or already at the lowest group — either way the
                // player goes back into the pool AVAILABLE so it can be put on the
                // block again. Group E is a sticky floor, never terminally unsold.
                player.setStatus(PlayerStatus.AVAILABLE);
            } else {
                // Withdrawn before ever going under auction, demotion disabled, or a
                // terminal group with no onward transition → finally unsold.
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

            AuctionProperties.Retention rules = ruleBook.current().retention();
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

            // RULE 2: retention fee. Either a flat per-group fee (legacy) or, when a
            // multiplier is configured, a multiple of the player's own base price
            // (e.g. 3× base) — see AuctionProperties.retentionCost.
            long price = ruleBook.current().retentionCost(player.getCategory(), player.getBasePrice());
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

    /**
     * Reverts a completed sale: the player goes back to the pool as AVAILABLE and
     * everything the sale touched is undone —
     *   - the buying team is refunded the sold price and the squad slot is freed;
     *   - the player's sale fields (team / price / timestamp) are cleared;
     *   - the player's persisted bid trail is dropped, so a re-auction starts
     *     fresh from base price instead of replaying the void bids;
     *   - the player's audit rows (the SOLD entry) are removed, so reports and
     *     the broadcast no longer count a sale that no longer stands.
     * A RELEASED audit row is written to record who reverted it and when.
     *
     * Category / base price are left untouched — a straight sale never changed
     * them, unlike the unsold cascade. RETAINED players use {@link #releasePlayer}.
     */
    @Transactional
    public SaleResult revertSale(UUID playerId) {
        synchronized (lock) {
            Player player = requirePlayer(playerId);
            if (player.getStatus() != PlayerStatus.SOLD) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is %s — only SOLD players can be reverted to the pool"
                                .formatted(player.getName(), player.getStatus()));
            }
            Team team = teams.findById(player.getSoldToTeamId()).orElseThrow(() ->
                    AuctionException.notFound("TEAM_NOT_FOUND",
                            "Buying team no longer exists: " + player.getSoldToTeamId()));
            long refund = player.getSoldPrice();

            player.setStatus(PlayerStatus.AVAILABLE);
            player.setSoldToTeamId(null);
            player.setSoldPrice(null);
            player.setSoldAt(null);
            players.save(player);

            team.setRemainingPurse(team.getRemainingPurse() + refund);
            team.getSquadPlayerIds().remove(player.getPlayerId());
            teams.save(team);

            // Wipe the void auction trail and the now-defunct SOLD audit row, then
            // leave a RELEASED breadcrumb documenting the reversal.
            bidding.discardBids(playerId);
            sales.deleteByPlayerId(playerId);
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
