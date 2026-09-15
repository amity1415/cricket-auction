package com.auctiontracker.bidding;

import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.Money;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerRepository;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import com.auctiontracker.core.TeamRepository;
import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Facade of the bidding module: mark-under-auction, place-bid, undo-bid
 * (DESIGN.md 5.2 / 5.3). Live bids are held in a {@link LiveBidStore} — nothing
 * touches the database per bid. The trail is flushed to BidEvent rows inside the
 * confirm-sale / mark-unsold transaction (see the sale module), so the audit
 * replay survives while mid-auction clicks stay instant and undoable. Trade-off:
 * a crash mid-bidding loses the in-flight bids; the player remains UNDER_AUCTION
 * and bidding simply restarts from base price.
 *
 * <p>The store is keyed by tournament, so each auction keeps its own on-the-block
 * player and trail. Which store backs it — a single JVM's memory, or a shared
 * Redis for a horizontally-scaled deployment — is a configuration choice
 * ({@link LiveBidStoreConfig}); this service is identical either way. The
 * authoritative "first request wins on a tie" guarantee lives in
 * {@link LiveBidStore#tryPush}: the {@code synchronized (lock)} here only serializes
 * a single instance's writes (and guards against double-submits), while tryPush is
 * what stays atomic across instances.
 */
@Service
public class BiddingService {

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final BidEventRepository bidEvents;
    private final IncrementRuleEngine incrementEngine;
    private final FeasibilityService feasibility;
    private final AuctionLock lock;
    private final RuleBook ruleBook;
    private final LiveBidStore store;

    public BiddingService(PlayerRepository players, TeamRepository teams, BidEventRepository bidEvents,
                          IncrementRuleEngine incrementEngine, FeasibilityService feasibility,
                          AuctionLock lock, RuleBook ruleBook, LiveBidStore store) {
        this.players = players;
        this.teams = teams;
        this.bidEvents = bidEvents;
        this.incrementEngine = incrementEngine;
        this.feasibility = feasibility;
        this.lock = lock;
        this.ruleBook = ruleBook;
        this.store = store;
    }

    /** The active tournament id used to key the live store (null before any exists). */
    private UUID tid() {
        return ruleBook.activeTournamentId();
    }

    /** The online auto-close window for the active auction, or null when it has no timer. */
    private Integer activeTimerSeconds() {
        AuctionProperties rules = ruleBook.current();
        return rules != null && rules.bidTimerEnabled() ? rules.bidTimerSeconds() : null;
    }

    /**
     * Puts a player on the block. Only one player can be under auction at a
     * time — any previous one goes back to AVAILABLE and its live bids are
     * discarded (they were never persisted).
     */
    @Transactional
    public Player markUnderAuction(UUID playerId) {
        synchronized (lock) {
            Player player = requirePlayer(playerId);
            if (player.getStatus() != PlayerStatus.AVAILABLE) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is %s — only AVAILABLE players can be put under auction"
                                .formatted(player.getName(), player.getStatus()));
            }
            players.findFirstByStatus(PlayerStatus.UNDER_AUCTION).ifPresent(previous -> {
                previous.setStatus(PlayerStatus.AVAILABLE);
                players.save(previous);
            });
            player.setStatus(PlayerStatus.UNDER_AUCTION);
            Player saved = players.save(player);
            // Open a fresh trail and, for an online auction, arm the auto-close timer.
            store.open(tid(), playerId, activeTimerSeconds());
            return saved;
        }
    }

    public record BidResult(Player player, Team leadingTeam, long amount, int bidNumber,
                            long nextMinimumIncrement) {}

    /**
     * Records a bid for a team at the server-computed next increment — in the live
     * store only, no database write (DESIGN.md 5.3).
     */
    public BidResult placeBid(UUID playerId, UUID teamId) {
        return placeBid(playerId, teamId, null);
    }

    /**
     * Records a bid for a team. When {@code customAmount} is null the server uses
     * the computed next increment (the normal quick-bid path). When it is set —
     * an owner or the auctioneer typed a floor/verbal bid — that exact amount is
     * used instead, provided it clears the base price and beats the current bid.
     * Either way it runs the full acquire-time feasibility check (purse, squad,
     * group quota, reserve), then commits atomically through {@link LiveBidStore#tryPush}
     * so a concurrent bid at the same amount can never both win.
     */
    public BidResult placeBid(UUID playerId, UUID teamId, Long customAmount) {
        synchronized (lock) {
            UUID tid = tid();
            Integer timer = activeTimerSeconds();
            Player player = requirePlayer(playerId);
            Team team = teams.findById(teamId).orElseThrow(() ->
                    AuctionException.notFound("TEAM_NOT_FOUND", "No team with id " + teamId));

            if (player.getStatus() != PlayerStatus.UNDER_AUCTION) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is not under auction (status: %s) — mark them under auction first"
                                .formatted(player.getName(), player.getStatus()));
            }
            store.ensureOpen(tid, playerId, timer);
            LiveBidStore.Step leading = store.last(tid, playerId);
            if (leading != null && teamId.equals(leading.teamId())) {
                throw AuctionException.conflict("SELF_OUTBID",
                        "%s is already the leading bidder — a team cannot outbid itself"
                                .formatted(team.getName()));
            }

            long amount;
            if (customAmount != null) {
                if (customAmount < player.getBasePrice()) {
                    throw AuctionException.badRequest("BID_TOO_LOW",
                            "Bid %s is below %s's base price of %s".formatted(
                                    Money.inr(customAmount), player.getName(), Money.inr(player.getBasePrice())));
                }
                if (leading != null && customAmount <= leading.amount()) {
                    throw AuctionException.conflict("BID_TOO_LOW",
                            "Bid %s must be higher than the current bid of %s".formatted(
                                    Money.inr(customAmount), Money.inr(leading.amount())));
                }
                amount = customAmount;
            } else {
                amount = incrementEngine.nextBidAmount(player.getBasePrice(),
                        leading == null ? null : leading.amount());
            }
            feasibility.assertCanAcquire(team, player, amount);

            // Authoritative commit: atomic across instances. Re-validates against the
            // current leader, so if another owner slipped in the same/higher amount
            // between our read above and here, we lose the race and report it.
            LiveBidStore.PushOutcome out = store.tryPush(tid, playerId, teamId, amount,
                    player.getBasePrice(), timer);
            switch (out.status()) {
                case OK -> {
                    return new BidResult(player, team, amount, out.count(),
                            incrementEngine.incrementFor(amount));
                }
                case SELF_OUTBID -> throw AuctionException.conflict("SELF_OUTBID",
                        "%s is already the leading bidder — a team cannot outbid itself"
                                .formatted(team.getName()));
                case TOO_LOW -> throw AuctionException.conflict("BID_TOO_LOW",
                        "Bid %s must be higher than the current bid of %s".formatted(
                                Money.inr(amount), Money.inr(out.leadingAmount())));
                case BELOW_BASE -> throw AuctionException.badRequest("BID_TOO_LOW",
                        "Bid %s is below %s's base price of %s".formatted(
                                Money.inr(amount), player.getName(), Money.inr(player.getBasePrice())));
                default -> throw AuctionException.conflict("INVALID_STATE",
                        "%s is no longer on the block — bidding has moved on".formatted(player.getName()));
            }
        }
    }

    /**
     * Undoes the most recent bid (misclick guard) — store only, nothing to roll
     * back in the database. Repeatable down to zero bids.
     */
    public Player undoBid(UUID playerId) {
        synchronized (lock) {
            UUID tid = tid();
            Player player = requirePlayer(playerId);
            if (player.getStatus() != PlayerStatus.UNDER_AUCTION || !store.isFor(tid, playerId)) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is not under auction — nothing to undo".formatted(player.getName()));
            }
            if (store.popLast(tid, playerId, activeTimerSeconds()) == null) {
                throw AuctionException.conflict("NO_BIDS",
                        "No bids on %s yet — nothing to undo".formatted(player.getName()));
            }
            return player;
        }
    }

    /**
     * Records a bid amount the auctioneer called out by VOICE before naming the
     * team ("fifty thousand!" with no team yet). Display only — no DB write and
     * no feasibility check. It must clear the base price and beat the current
     * committed bid. Returns the accepted amount.
     */
    public long setVerbalBid(UUID playerId, long amount) {
        synchronized (lock) {
            UUID tid = tid();
            Player player = requirePlayer(playerId);
            if (player.getStatus() != PlayerStatus.UNDER_AUCTION) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is not under auction (status: %s) — mark them under auction first"
                                .formatted(player.getName(), player.getStatus()));
            }
            store.ensureOpen(tid, playerId, activeTimerSeconds());
            if (amount < player.getBasePrice()) {
                throw AuctionException.badRequest("BID_TOO_LOW",
                        "Bid %s is below %s's base price of %s".formatted(
                                Money.inr(amount), player.getName(), Money.inr(player.getBasePrice())));
            }
            LiveBidStore.Step leading = store.last(tid, playerId);
            if (leading != null && amount <= leading.amount()) {
                throw AuctionException.conflict("BID_TOO_LOW",
                        "Bid %s must be higher than the current bid of %s".formatted(
                                Money.inr(amount), Money.inr(leading.amount())));
            }
            store.setPendingVerbal(tid, playerId, amount);
            return amount;
        }
    }

    /** Cancels a pending voice amount (misrecognition / auctioneer correction). */
    public void clearVerbalBid(UUID playerId) {
        store.setPendingVerbal(tid(), playerId, null);
    }

    /** The un-attributed voice amount for the on-block player, or null. */
    public Long pendingVerbalBidAmount(UUID playerId) {
        return store.pendingVerbal(tid(), playerId);
    }

    /** Current live price for the player, or null if no bids (or not live). */
    public Long currentBidAmount(UUID playerId) {
        LiveBidStore.Step last = store.last(tid(), playerId);
        return last == null ? null : last.amount();
    }

    /**
     * The player currently on the block for the active tournament, straight from
     * the live store — no DB. Null when nothing is live.
     */
    public UUID currentBlockPlayerId() {
        return store.currentPlayer(tid());
    }

    /** Current leading team, or null if no bids (or not live). */
    public UUID currentLeadingTeamId(UUID playerId) {
        LiveBidStore.Step last = store.last(tid(), playerId);
        return last == null ? null : last.teamId();
    }

    /** The absolute auto-close instant for the on-block player, or null. */
    public Instant currentBlockDeadline(UUID playerId) {
        return store.deadline(tid(), playerId);
    }

    /**
     * The full read-side view of a player's live state, computed from a SINGLE
     * store round-trip (one Redis hop when Redis-backed) instead of the six
     * separate calls the dashboard/current-bid/live-state views used to make.
     * When the player is not the one on the block, {@code bidCount} falls back to
     * the persisted trail so post-auction views still show the historic count.
     */
    public record BlockView(boolean live, Long currentAmount, UUID leadingTeamId,
                            int bidCount, Long pendingVerbalAmount, Instant deadline,
                            long nextBidAmount) {}

    public BlockView blockView(Player player) {
        LiveBidStore.Snapshot s = store.snapshot(tid(), player.getPlayerId());
        long next = incrementEngine.nextBidAmount(player.getBasePrice(),
                s.live() ? s.leadingAmount() : null);
        int count = s.live() ? s.count() : (int) bidEvents.countByPlayerId(player.getPlayerId());
        return new BlockView(s.live(), s.leadingAmount(), s.leadingTeam(), count,
                s.pendingVerbal(), s.deadline(), next);
    }

    /** The bid that confirm-sale would commit, if any. */
    public Optional<LeadingBid> leadingBid(UUID playerId) {
        LiveBidStore.Step last = store.last(tid(), playerId);
        return last == null ? Optional.empty()
                : Optional.of(new LeadingBid(last.teamId(), last.amount()));
    }

    public record LeadingBid(UUID teamId, long amount) {}

    /**
     * Persists the live trail as BidEvent rows and closes the session. Called by
     * the sale module inside the confirm-sale / mark-unsold transaction, so the
     * audit replay commits (or rolls back) together with the outcome.
     */
    @Transactional
    public void flushLiveBids(UUID playerId) {
        synchronized (lock) {
            final UUID tid = tid();
            if (!store.isFor(tid, playerId)) {
                return;
            }
            int number = 0;
            for (LiveBidStore.Step step : store.steps(tid, playerId)) {
                bidEvents.save(BidEvent.record(playerId, step.teamId(), step.amount(), ++number, step.at()));
            }
            // Close only AFTER the surrounding sale transaction commits. If we closed
            // now, there is a window (a remote-DB commit can take hundreds of ms) where
            // a reader sees the player still UNDER_AUCTION but the live bids already
            // gone — which flashes the opening price on the broadcast just before the
            // sold screen. Deferring the close keeps the last bid on screen right up to
            // the moment the sale lands. On rollback the session stays open so bidding
            // can resume.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        store.close(tid, playerId);
                    }
                });
            } else {
                store.close(tid, playerId);
            }
        }
    }

    /** Setup-time reset (player pool is being replaced). */
    public void clearLiveSession() {
        synchronized (lock) {
            UUID tid = tid();
            UUID player = store.currentPlayer(tid);
            if (player != null) {
                store.close(tid, player);
            }
        }
    }

    /** Drops a tournament's live state entirely (used when it is deleted). */
    public void forgetTournament(UUID tournamentId) {
        if (tournamentId != null) {
            store.forget(tournamentId);
        }
    }

    /** Setup-time wipe (players are being replaced); not used mid-auction. */
    @Transactional
    public void deleteAllBidEvents() {
        bidEvents.deleteAll();
    }

    /**
     * Drops a single player's persisted bid trail (and any stray live session for
     * them). Called by the sale module when a sale is reverted, so the player
     * returns to the pool clean and a fresh auction starts from base price rather
     * than replaying the old, now-void trail.
     */
    @Transactional
    public void discardBids(UUID playerId) {
        synchronized (lock) {
            UUID tid = tid();
            if (store.isFor(tid, playerId)) {
                store.close(tid, playerId);
            }
            bidEvents.deleteByPlayerId(playerId);
        }
    }

    /** Live trail from the store while under auction; persisted rows afterwards. */
    public List<BidEvent> bidHistory(UUID playerId) {
        requirePlayer(playerId);
        UUID tid = tid();
        if (store.isFor(tid, playerId)) {
            int number = 0;
            List<LiveBidStore.Step> steps = store.steps(tid, playerId);
            List<BidEvent> live = new ArrayList<>(steps.size());
            for (LiveBidStore.Step step : steps) {
                live.add(BidEvent.record(playerId, step.teamId(), step.amount(), ++number, step.at()));
            }
            return live;
        }
        return bidEvents.findByPlayerIdOrderByBidNumberAsc(playerId);
    }

    public int bidCount(UUID playerId) {
        UUID tid = tid();
        if (store.isFor(tid, playerId)) {
            return store.count(tid, playerId);
        }
        return (int) bidEvents.countByPlayerId(playerId);
    }

    /** Exposed for read-side views (dashboard, current-bid endpoint). */
    public long nextBidAmount(Player player) {
        return incrementEngine.nextBidAmount(player.getBasePrice(),
                currentBidAmount(player.getPlayerId()));
    }

    private Player requirePlayer(UUID playerId) {
        return players.findById(playerId).orElseThrow(() ->
                AuctionException.notFound("PLAYER_NOT_FOUND", "No player with id " + playerId));
    }
}
