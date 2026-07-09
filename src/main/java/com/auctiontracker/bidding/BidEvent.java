package com.auctiontracker.bidding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per bid — the full replay of how a player's price climbed
 * (DESIGN.md 3.3). Amount is always server-computed, never client-supplied.
 */
@Entity
@Table(name = "bid_event")
public class BidEvent {

    @Id
    private UUID bidEventId;

    @Column(nullable = false)
    private UUID playerId;

    @Column(nullable = false)
    private UUID teamId;

    private long amount;
    private int bidNumber;
    private Instant recordedAt;

    public static BidEvent record(UUID playerId, UUID teamId, long amount, int bidNumber) {
        return record(playerId, teamId, amount, bidNumber, Instant.now());
    }

    /** Used when flushing the in-memory trail — keeps the original bid timestamps. */
    public static BidEvent record(UUID playerId, UUID teamId, long amount, int bidNumber, Instant recordedAt) {
        BidEvent e = new BidEvent();
        e.bidEventId = UUID.randomUUID();
        e.playerId = playerId;
        e.teamId = teamId;
        e.amount = amount;
        e.bidNumber = bidNumber;
        e.recordedAt = recordedAt;
        return e;
    }

    public UUID getBidEventId() { return bidEventId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getTeamId() { return teamId; }
    public long getAmount() { return amount; }
    public int getBidNumber() { return bidNumber; }
    public Instant getRecordedAt() { return recordedAt; }
}
