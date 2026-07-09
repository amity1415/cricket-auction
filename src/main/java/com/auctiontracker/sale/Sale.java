package com.auctiontracker.sale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit record (DESIGN.md 3.4). Every confirmed sale AND every unsold decision
 * is written here — together with BidEvent this replays the whole auction.
 * teamId/teamName/amount are null for UNSOLD entries.
 */
@Entity
@Table(name = "sale")
public class Sale {

    public enum Type { SOLD, UNSOLD, RETAINED, RELEASED }

    @Id
    private UUID saleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_type", nullable = false)
    private Type type;

    @Column(nullable = false)
    private UUID playerId;

    private String playerName;
    private UUID teamId;
    private String teamName;
    private Long amount;
    private String recordedBy;
    private Instant recordedAt;

    public static Sale sold(UUID playerId, String playerName, UUID teamId, String teamName,
                            long amount, String recordedBy) {
        Sale s = base(playerId, playerName, recordedBy);
        s.type = Type.SOLD;
        s.teamId = teamId;
        s.teamName = teamName;
        s.amount = amount;
        return s;
    }

    public static Sale unsold(UUID playerId, String playerName, String recordedBy) {
        Sale s = base(playerId, playerName, recordedBy);
        s.type = Type.UNSOLD;
        return s;
    }

    /** Pre-auction retention: player joins the team's squad at base price. */
    public static Sale retained(UUID playerId, String playerName, UUID teamId, String teamName,
                                long amount, String recordedBy) {
        Sale s = base(playerId, playerName, recordedBy);
        s.type = Type.RETAINED;
        s.teamId = teamId;
        s.teamName = teamName;
        s.amount = amount;
        return s;
    }

    /** Retention undone: player back to the pool, purse refunded. */
    public static Sale released(UUID playerId, String playerName, UUID teamId, String teamName,
                                long amount, String recordedBy) {
        Sale s = base(playerId, playerName, recordedBy);
        s.type = Type.RELEASED;
        s.teamId = teamId;
        s.teamName = teamName;
        s.amount = amount;
        return s;
    }

    private static Sale base(UUID playerId, String playerName, String recordedBy) {
        Sale s = new Sale();
        s.saleId = UUID.randomUUID();
        s.playerId = playerId;
        s.playerName = playerName;
        s.recordedBy = recordedBy;
        s.recordedAt = Instant.now();
        return s;
    }

    public UUID getSaleId() { return saleId; }
    public Type getType() { return type; }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public UUID getTeamId() { return teamId; }
    public String getTeamName() { return teamName; }
    public Long getAmount() { return amount; }
    public String getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
