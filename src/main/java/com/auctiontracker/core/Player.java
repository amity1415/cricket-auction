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

    /**
     * Insertion order within the tournament (0-based), so lists can show players
     * in the order they were registered / imported from the CSV rather than an
     * arbitrary DB order. Nullable for rows created before this existed — those
     * sort last (see the repository's ordered queries).
     */
    @Column(name = "seq")
    private Integer seq;

    /** Career profile shown to the room while the player is on the block. */
    @Embedded
    private PlayerStats stats;

    /**
     * Google Drive file id of this player's resolved poster image, or null when
     * none. Resolved by {@link com.auctiontracker.photo.PlayerPhotoService} as the
     * file named by the player's serial ({@link #seq} + 1) inside the photo folder
     * — the import's {@code Image_location} folder ({@link #photoFolderId}) when
     * given, else the configured folder. Persisted, so it survives restarts;
     * served via GET /api/players/{id}/photo.
     */
    @Column(name = "photo_file_id")
    private String photoFileId;

    /**
     * Transient (never persisted) Google Drive FOLDER reference supplied by an
     * import's {@code Image_location} column. The player's poster is the file
     * named by its 1-based serial ({@link #seq} + 1) inside this folder; the
     * photo service resolves that to {@link #photoFileId} right after import.
     * In-memory only — it carries the folder from the parser to that resolution
     * step without adding a column.
     */
    @jakarta.persistence.Transient
    private String photoFolderId;

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

    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }

    public PlayerStats getStats() { return stats; }
    public void setStats(PlayerStats stats) { this.stats = stats; }

    public String getPhotoFileId() { return photoFileId; }
    public void setPhotoFileId(String photoFileId) { this.photoFileId = photoFileId; }

    public String getPhotoFolderId() { return photoFolderId; }
    public void setPhotoFolderId(String photoFolderId) { this.photoFolderId = photoFolderId; }

    /** True when this player has a poster image mapped (served at /api/players/{id}/photo). */
    public boolean hasPhoto() { return photoFileId != null && !photoFileId.isBlank(); }

    public PlayerStatus getStatus() { return status; }
    public void setStatus(PlayerStatus status) { this.status = status; }

    public UUID getSoldToTeamId() { return soldToTeamId; }
    public void setSoldToTeamId(UUID soldToTeamId) { this.soldToTeamId = soldToTeamId; }

    public Long getSoldPrice() { return soldPrice; }
    public void setSoldPrice(Long soldPrice) { this.soldPrice = soldPrice; }

    public Instant getSoldAt() { return soldAt; }
    public void setSoldAt(Instant soldAt) { this.soldAt = soldAt; }
}
