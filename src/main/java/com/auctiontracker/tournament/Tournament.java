package com.auctiontracker.tournament;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
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

    /** The tournament's rule book — a serialized AuctionProperties (see RuleBook). */
    @Lob
    @Column(nullable = false, columnDefinition = "text")
    private String rulesJson;

    @Column(nullable = false)
    private Instant createdAt;

    /** Exactly one tournament is active at a time — the one everyone operates in. */
    @Column(nullable = false)
    private boolean active;

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
}
