package com.auctiontracker.bidding;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * In-memory bid trail for the one player currently on the block. Bids live
 * here (undoable, never persisted) until confirm-sale or mark-unsold flushes
 * them to the database in the same transaction as the outcome. Lost on
 * restart by design — the player stays UNDER_AUCTION and bidding restarts.
 *
 * Not thread-safe on its own: all access goes through BiddingService, which
 * synchronizes on the shared AuctionLock.
 */
class LiveBidSession {

    record Step(UUID teamId, long amount, Instant at) {}

    private UUID playerId;
    private final Deque<Step> steps = new ArrayDeque<>();

    /**
     * A bid amount the auctioneer has called out by VOICE but not yet attributed
     * to a team ("fifty thousand!" before naming who bid). It is display-only —
     * it never becomes a real bid until a team is named, at which point a normal
     * {@link #push} commits it and clears this. Held here (not just in the
     * browser) so the audience broadcast screen — a different device — can show
     * the rising amount with the team logo hidden until the team is spoken.
     * Null whenever there is no un-attributed amount pending.
     */
    private Long pendingVerbalAmount;

    /** Starts a fresh trail for a player, discarding any previous one. */
    void open(UUID playerId) {
        this.playerId = playerId;
        steps.clear();
        pendingVerbalAmount = null;
    }

    void close() {
        playerId = null;
        steps.clear();
        pendingVerbalAmount = null;
    }

    boolean isFor(UUID playerId) {
        return playerId != null && playerId.equals(this.playerId);
    }

    /** The player currently on the block in this session, or null if none is open. */
    UUID currentPlayerId() {
        return playerId;
    }

    void push(UUID teamId, long amount) {
        steps.addLast(new Step(teamId, amount, Instant.now()));
        // A committed bid supersedes any un-attributed verbal amount.
        pendingVerbalAmount = null;
    }

    /** Records/updates the un-attributed verbal amount (display only). */
    void setPendingVerbalAmount(Long amount) {
        this.pendingVerbalAmount = amount;
    }

    Long pendingVerbalAmount() {
        return pendingVerbalAmount;
    }

    /** Removes and returns the most recent bid, or null if there are none. */
    Step popLast() {
        return steps.pollLast();
    }

    Step last() {
        return steps.peekLast();
    }

    int count() {
        return steps.size();
    }

    List<Step> stepsInOrder() {
        return List.copyOf(steps);
    }
}
