package com.auctiontracker.tournament;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A distinct auction/tournament (e.g. "KCPL"). Each tournament owns its own
 * players, teams, sales and franchise owners (scoped by tournament_id) and its
 * own rule book, stored here as JSON (a serialized {@code AuctionProperties}).
 * The admin picks the active tournament per session; everything then operates
 * within it. The very first tournament ("KCPL") is bootstrapped from the rules
 * in application.yml so existing data keeps behaving exactly as before.
 */
@Entity
@Table(name = "tournament")
public class Tournament {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Short URL/identity handle, unique. */
    @Column(nullable = false, unique = true)
    private String slug;

    /**
     * The tournament's rule book — a serialized AuctionProperties (see RuleBook).
     * Plain TEXT, NOT {@code @Lob}: on Postgres {@code @Lob String} becomes a large
     * object (OID) that can't be read outside a transaction, which 500s every
     * non-transactional read (see TournamentBootstrap's one-time migration).
     */
    @Column(nullable = false, columnDefinition = "text")
    private String rulesJson;

    @Column(nullable = false)
    private Instant createdAt;

    /** Exactly one tournament is active at a time — the one everyone operates in. */
    @Column(nullable = false)
    private boolean active;

    /**
     * Which auction format this tournament runs. A plain nullable string (not a
     * DB enum, so no CHECK constraint to keep in sync as formats are added);
     * {@code null} means the legacy {@code STANDARD_POOL}. The actual mechanics
     * are driven by the rule book (base prices, quotas, unsold transitions); this
     * is the label a tournament carries so screens can show/select its format.
     */
    @Column(name = "auction_rule_type")
    private String auctionRuleType;

    /**
     * Google Drive FOLDER id holding this tournament's player poster images, one
     * per player named by the player's 1-based import serial. Set from the auction
     * editor (the admin pastes an "anyone with the link" Drive folder link, which
     * is normalised to its id). Null = this tournament has no photo folder, so it
     * falls back to the app-wide {@code auction.photos.folder-id} (if any), else
     * players render with their initials. Resolved by
     * {@link com.auctiontracker.photo.PlayerPhotoService}.
     */
    @Column(name = "photos_folder_id")
    private String photosFolderId;

    /**
     * True once the admin marks the auction finished, which publishes the public
     * team-showcase page (see {@code showcase.html}). Nullable/opt-in so it adds a
     * plain column via ddl-auto=update without a NOT NULL default that would break
     * inserts on the shared DB; null is treated as "not complete".
     */
    @Column(name = "auction_complete")
    private Boolean auctionComplete;

    protected Tournament() {
        // for JPA
    }

    public static Tournament create(String name, String slug, String rulesJson) {
        Tournament t = new Tournament();
        t.id = UUID.randomUUID();
        t.name = name;
        t.slug = slug;
        t.rulesJson = rulesJson;
        t.createdAt = Instant.now();
        t.active = false;
        return t;
    }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }
    public Instant getCreatedAt() { return createdAt; }

    /** The auction format label; defaults to STANDARD_POOL when unset (legacy rows). */
    public String getAuctionRuleType() {
        return auctionRuleType == null ? "STANDARD_POOL" : auctionRuleType;
    }
    public void setAuctionRuleType(String auctionRuleType) { this.auctionRuleType = auctionRuleType; }

    /** Drive folder id for this tournament's player photos; null if none configured. */
    public String getPhotosFolderId() { return photosFolderId; }
    public void setPhotosFolderId(String photosFolderId) { this.photosFolderId = photosFolderId; }

    /** True once the admin has marked the auction finished (null = not complete). */
    public boolean isAuctionComplete() { return Boolean.TRUE.equals(auctionComplete); }
    public void setAuctionComplete(boolean complete) { this.auctionComplete = complete; }
}
