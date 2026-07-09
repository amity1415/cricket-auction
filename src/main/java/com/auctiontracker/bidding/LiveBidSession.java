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

    /** Starts a fresh trail for a player, discarding any previous one. */
    void open(UUID playerId) {
        this.playerId = playerId;
        steps.clear();
    }

    void close() {
        playerId = null;
        steps.clear();
    }

    boolean isFor(UUID playerId) {
        return playerId != null && playerId.equals(this.playerId);
    }

    void push(UUID teamId, long amount) {
        steps.addLast(new Step(teamId, amount, Instant.now()));
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
