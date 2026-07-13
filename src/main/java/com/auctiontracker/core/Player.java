package com.auctiontracker.core;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA entity. Money fields are whole rupees. */
@Entity
@Table(name = "player")
public class Player {

    @Id
    private UUID playerId;

    /** Which tournament this player belongs to (nullable for old rows; backfilled). */
    @Column(name = "tournament_id")
    private UUID tournamentId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlayerRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlayerCategory category;

    private long basePrice;

    /** Career profile shown to the room while the player is on the block. */
    @Embedded
    private PlayerStats stats;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlayerStatus status;

    // Live bidding state deliberately does NOT live here: while a player is
    // UNDER_AUCTION the bid trail is held in memory (bidding.LiveBidSession)
    // and only flushed to the database when the sale/unsold outcome commits.

    // Sale outcome — only set once SOLD.
    private UUID soldToTeamId;
    private Long soldPrice;
    private Instant soldAt;

    public static Player register(String name, PlayerRole role, PlayerCategory category,
                                  long basePrice) {
        Player p = new Player();
        p.playerId = UUID.randomUUID();
        p.name = name;
        p.role = role;
        p.category = category;
        p.basePrice = basePrice;
        p.status = PlayerStatus.AVAILABLE;
        return p;
    }

    public UUID getPlayerId() { return playerId; }
    public void setPlayerId(UUID playerId) { this.playerId = playerId; }

    public UUID getTournamentId() { return tournamentId; }
    public void setTournamentId(UUID tournamentId) { this.tournamentId = tournamentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public PlayerRole getRole() { return role; }
    public void setRole(PlayerRole role) { this.role = role; }

    public PlayerCategory getCategory() { return category; }
    public void setCategory(PlayerCategory category) { this.category = category; }

    public long getBasePrice() { return basePrice; }
    public void setBasePrice(long basePrice) { this.basePrice = basePrice; }

    public PlayerStats getStats() { return stats; }
    public void setStats(PlayerStats stats) { this.stats = stats; }

    public PlayerStatus getStatus() { return status; }
    public void setStatus(PlayerStatus status) { this.status = status; }

    public UUID getSoldToTeamId() { return soldToTeamId; }
    public void setSoldToTeamId(UUID soldToTeamId) { this.soldToTeamId = soldToTeamId; }

    public Long getSoldPrice() { return soldPrice; }
    public void setSoldPrice(Long soldPrice) { this.soldPrice = soldPrice; }

    public Instant getSoldAt() { return soldAt; }
    public void setSoldAt(Instant soldAt) { this.soldAt = soldAt; }
}
