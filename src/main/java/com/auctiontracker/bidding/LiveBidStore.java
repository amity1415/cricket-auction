package com.auctiontracker.bidding;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The authoritative live-bidding state for the one player on the block, keyed by
 * tournament. Two implementations back it:
 *
 * <ul>
 *   <li>{@link InMemoryLiveBidStore} — a single JVM's memory guarded by the
 *       auction lock (the original, default behaviour; no external dependency).</li>
 *   <li>{@code RedisLiveBidStore} — a shared Redis with an atomic compare-and-set,
 *       so several app instances behind a load balancer stay consistent and a tie
 *       between owners on different instances resolves to exactly one winner.</li>
 * </ul>
 *
 * The store owns only the ephemeral trail (never persisted here — bids land in the
 * database on sale/unsold) and the online auto-close deadline. Every mutation is
 * atomic with respect to concurrent callers: {@link #tryPush} is the single point
 * that decides a bid, and {@link #claimExpiredClose} hands the expired lot to
 * exactly one caller so the timer fires once cluster-wide.
 */
public interface LiveBidStore {

    /** One bid in the trail. {@code at} is when it was accepted. */
    record Step(UUID teamId, long amount, Instant at) {}

    /** Why a {@link #tryPush} was accepted or rejected. */
    enum PushStatus { OK, NOT_LIVE, SELF_OUTBID, TOO_LOW, BELOW_BASE }

    /**
     * Outcome of an atomic push. On {@code OK}, {@code count} is the new bid count.
     * Otherwise {@code leadingAmount} is the current leading amount (for the error
     * message), or null when there is none.
     */
    record PushOutcome(PushStatus status, int count, Long leadingAmount) {}

    /**
     * The whole live state for one player, read in a SINGLE round-trip. {@code live}
     * is whether that player is the one on the block; the rest are meaningful only
     * when live (leading bid, trail size, pending voice amount, auto-close deadline).
     * Read-side views use this instead of calling {@link #last}/{@link #count}/
     * {@link #deadline}/etc. separately, so a poll costs one Redis hop, not six.
     */
    record Snapshot(boolean live, Long leadingAmount, UUID leadingTeam, int count,
                    Long pendingVerbal, Instant deadline) {}

    /** The player currently on the block for this tournament, or null. */
    UUID currentPlayer(UUID tournamentId);

    /** Starts a fresh trail for a player (discards any previous), arming the timer
     *  when {@code timerSeconds} is non-null. */
    void open(UUID tournamentId, UUID playerId, Integer timerSeconds);

    /** Opens the trail for the player only if it is not already the current one
     *  (recovers a lot whose in-memory trail was lost to a restart). */
    void ensureOpen(UUID tournamentId, UUID playerId, Integer timerSeconds);

    /** Closes the trail iff it is currently for {@code playerId}. */
    void close(UUID tournamentId, UUID playerId);

    boolean isFor(UUID tournamentId, UUID playerId);

    /**
     * Atomically appends a bid after re-validating against the current leader:
     * a bidder cannot outbid itself, the amount must beat the current leading bid
     * (or clear the base price when there are none). Resets the auto-close timer
     * when {@code timerSeconds} is non-null. This is where first-request-wins is
     * decided — under contention exactly one caller gets {@code OK} for a given price.
     */
    PushOutcome tryPush(UUID tournamentId, UUID playerId, UUID teamId, long amount,
                        long basePrice, Integer timerSeconds);

    /** Removes and returns the most recent bid (undo), resetting the timer, or null. */
    Step popLast(UUID tournamentId, UUID playerId, Integer timerSeconds);

    /** The current leading bid, or null. */
    Step last(UUID tournamentId, UUID playerId);

    int count(UUID tournamentId, UUID playerId);

    List<Step> steps(UUID tournamentId, UUID playerId);

    void setPendingVerbal(UUID tournamentId, UUID playerId, Long amount);

    Long pendingVerbal(UUID tournamentId, UUID playerId);

    /** The absolute auto-close instant for the on-block player, or null. */
    Instant deadline(UUID tournamentId, UUID playerId);

    /** The full live state for a player in one round-trip (see {@link Snapshot}). */
    Snapshot snapshot(UUID tournamentId, UUID playerId);

    /**
     * If the on-block lot's auto-close deadline has passed, atomically claims it —
     * returning the player id to close — and clears the deadline so no other caller
     * (or instance) can claim the same lot. Empty when nothing is due.
     */
    Optional<UUID> claimExpiredClose(UUID tournamentId);

    /** Drops all state for a tournament (used when it is deleted). */
    void forget(UUID tournamentId);
}
