package com.auctiontracker.bidding;

import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerRepository;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import com.auctiontracker.core.TeamRepository;
import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facade of the bidding module: mark-under-auction, place-bid, undo-bid
 * (DESIGN.md 5.2 / 5.3). Live bids are held in an in-memory {@link LiveBidSession}
 * — nothing touches the database per bid. The trail is flushed to BidEvent rows
 * inside the confirm-sale / mark-unsold transaction (see the sale module), so
 * the audit replay survives while mid-auction clicks stay instant and undoable.
 * Trade-off: a crash mid-bidding loses the in-flight bids; the player remains
 * UNDER_AUCTION and bidding simply restarts from base price.
 *
 * Each tournament keeps its OWN live session, keyed by tournament id — switching
 * the active tournament preserves (rather than clobbers) another tournament's
 * on-the-block player and bid trail.
 */
@Service
public class BiddingService {

    /** Map key used before any tournament exists (fresh boot / tests). */
    private static final UUID NO_TOURNAMENT = new UUID(0L, 0L);

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final BidEventRepository bidEvents;
    private final IncrementRuleEngine incrementEngine;
    private final FeasibilityService feasibility;
    private final AuctionLock lock;
    private final RuleBook ruleBook;
    private final Map<UUID, LiveBidSession> sessions = new ConcurrentHashMap<>();

    public BiddingService(PlayerRepository players, TeamRepository teams, BidEventRepository bidEvents,
                          IncrementRuleEngine incrementEngine, FeasibilityService feasibility,
                          AuctionLock lock, RuleBook ruleBook) {
        this.players = players;
        this.teams = teams;
        this.bidEvents = bidEvents;
        this.incrementEngine = incrementEngine;
        this.feasibility = feasibility;
        this.lock = lock;
        this.ruleBook = ruleBook;
    }

    /** The live session of the active tournament (created on first use). */
    private LiveBidSession session() {
        UUID tid = ruleBook.activeTournamentId();
        return sessions.computeIfAbsent(tid == null ? NO_TOURNAMENT : tid, k -> new LiveBidSession());
    }

    /**
     * Puts a player on the block. Only one player can be under auction at a
     * time — any previous one goes back to AVAILABLE and its live bids are
     * discarded (they were never persisted).
     */
    @Transactional
    public Player markUnderAuction(UUID playerId) {
        synchronized (lock) {
            LiveBidSession session = session();
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
            session.open(playerId);
            player.setStatus(PlayerStatus.UNDER_AUCTION);
            return players.save(player);
        }
    }

    public record BidResult(Player player, Team leadingTeam, long amount, int bidNumber,
                            long nextMinimumIncrement) {}

    /**
     * Records a bid for a team — in memory only, no database write. The server
     * computes the price; the request deliberately carries no amount (DESIGN.md 5.3).
     */
    public BidResult placeBid(UUID playerId, UUID teamId) {
        synchronized (lock) {
            LiveBidSession session = session();
            Player player = requirePlayer(playerId);
            Team team = teams.findById(teamId).orElseThrow(() ->
                    AuctionException.notFound("TEAM_NOT_FOUND", "No team with id " + teamId));

            if (player.getStatus() != PlayerStatus.UNDER_AUCTION) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is not under auction (status: %s) — mark them under auction first"
                                .formatted(player.getName(), player.getStatus()));
            }
            ensureSessionFor(session, playerId);
            LiveBidSession.Step leading = session.last();
            if (leading != null && teamId.equals(leading.teamId())) {
                throw AuctionException.conflict("SELF_OUTBID",
                        "%s is already the leading bidder — a team cannot outbid itself"
                                .formatted(team.getName()));
            }

            long nextAmount = incrementEngine.nextBidAmount(player.getBasePrice(),
                    leading == null ? null : leading.amount());
            feasibility.assertCanAcquire(team, player, nextAmount);

            session.push(teamId, nextAmount);
            return new BidResult(player, team, nextAmount, session.count(),
                    incrementEngine.incrementFor(nextAmount));
        }
    }

    /**
     * Undoes the most recent bid (misclick guard) — cache only, nothing to
     * roll back in the database. Repeatable down to zero bids.
     */
    public Player undoBid(UUID playerId) {
        synchronized (lock) {
            LiveBidSession session = session();
            Player player = requirePlayer(playerId);
            if (player.getStatus() != PlayerStatus.UNDER_AUCTION || !session.isFor(playerId)) {
                throw AuctionException.conflict("INVALID_STATE",
                        "%s is not under auction — nothing to undo".formatted(player.getName()));
            }
            if (session.popLast() == null) {
                throw AuctionException.conflict("NO_BIDS",
                        "No bids on %s yet — nothing to undo".formatted(player.getName()));
            }
            return player;
        }
    }

    /** Current live price for the player, or null if no bids (or not live). */
    public Long currentBidAmount(UUID playerId) {
        synchronized (lock) {
            LiveBidSession session = session();
            LiveBidSession.Step last = session.isFor(playerId) ? session.last() : null;
            return last == null ? null : last.amount();
        }
    }

    /** Current leading team, or null if no bids (or not live). */
    public UUID currentLeadingTeamId(UUID playerId) {
        synchronized (lock) {
            LiveBidSession session = session();
            LiveBidSession.Step last = session.isFor(playerId) ? session.last() : null;
            return last == null ? null : last.teamId();
        }
    }

    /** The bid that confirm-sale would commit, if any. */
    public Optional<LeadingBid> leadingBid(UUID playerId) {
        synchronized (lock) {
            LiveBidSession session = session();
            LiveBidSession.Step last = session.isFor(playerId) ? session.last() : null;
            return last == null ? Optional.empty()
                    : Optional.of(new LeadingBid(last.teamId(), last.amount()));
        }
    }

    public record LeadingBid(UUID teamId, long amount) {}

    /**
     * Persists the live trail as BidEvent rows and closes the session. Called
     * by the sale module inside the confirm-sale / mark-unsold transaction, so
     * the audit replay commits (or rolls back) together with the outcome.
     */
    @Transactional
    public void flushLiveBids(UUID playerId) {
        synchronized (lock) {
            final LiveBidSession session = session();
            if (!session.isFor(playerId)) {
                return;
            }
            int number = 0;
            for (LiveBidSession.Step step : session.stepsInOrder()) {
                bidEvents.save(BidEvent.record(playerId, step.teamId(), step.amount(), ++number, step.at()));
            }
            // Close the session only AFTER the surrounding sale transaction commits.
            // If we closed it now, there is a window (the commit against a remote DB
            // can take hundreds of ms) where a reader sees the player still
            // UNDER_AUCTION (the SOLD status isn't committed yet) but the live bids
            // already gone — which flashes the opening price on the broadcast just
            // before the sold screen. Deferring the close keeps the last bid on
            // screen right up to the moment the sale lands. On rollback the session
            // stays open so bidding can resume.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        synchronized (lock) {
                            if (session.isFor(playerId)) {
                                session.close();
                            }
                        }
                    }
                });
            } else {
                session.close();
            }
        }
    }

    /** Setup-time reset (player pool is being replaced). */
    public void clearLiveSession() {
        synchronized (lock) {
            session().close();
        }
    }

    /** Drops a tournament's in-memory live session entirely (used when it is deleted). */
    public void forgetTournament(UUID tournamentId) {
        synchronized (lock) {
            if (tournamentId != null) {
                sessions.remove(tournamentId);
            }
        }
    }

    /** Setup-time wipe (players are being replaced); not used mid-auction. */
    @Transactional
    public void deleteAllBidEvents() {
        bidEvents.deleteAll();
    }

    /** Live trail from the cache while under auction; persisted rows afterwards. */
    public List<BidEvent> bidHistory(UUID playerId) {
        requirePlayer(playerId);
        synchronized (lock) {
            LiveBidSession session = session();
            if (session.isFor(playerId)) {
                int number = 0;
                List<LiveBidSession.Step> steps = session.stepsInOrder();
                List<BidEvent> live = new ArrayList<>(steps.size());
                for (LiveBidSession.Step step : steps) {
                    live.add(BidEvent.record(playerId, step.teamId(), step.amount(), ++number, step.at()));
                }
                return live;
            }
        }
        return bidEvents.findByPlayerIdOrderByBidNumberAsc(playerId);
    }

    public int bidCount(UUID playerId) {
        synchronized (lock) {
            LiveBidSession session = session();
            if (session.isFor(playerId)) {
                return session.count();
            }
        }
        return (int) bidEvents.countByPlayerId(playerId);
    }

    /** Exposed for read-side views (dashboard, current-bid endpoint). */
    public long nextBidAmount(Player player) {
        return incrementEngine.nextBidAmount(player.getBasePrice(),
                currentBidAmount(player.getPlayerId()));
    }

    /**
     * A player can be UNDER_AUCTION with no matching session after a restart
     * (live bids are memory-only). Reopen so bidding restarts from base price.
     */
    private void ensureSessionFor(LiveBidSession session, UUID playerId) {
        if (!session.isFor(playerId)) {
            session.open(playerId);
        }
    }

    private Player requirePlayer(UUID playerId) {
        return players.findById(playerId).orElseThrow(() ->
                AuctionException.notFound("PLAYER_NOT_FOUND", "No player with id " + playerId));
    }
}
