package com.auctiontracker.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A self-registered franchise-owner login. The admin account is NOT stored here
 * — it is authenticated from config (see {@link SecurityProperties}). Passwords
 * are stored only as a BCrypt hash, never in clear text.
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    private UUID id;

    /** Which tournament this owner belongs to (nullable for old rows; backfilled). */
    @Column(name = "tournament_id")
    private UUID tournamentId;

    @Column(nullable = false, unique = true)
    private String username;

    /** BCrypt hash of the password — never the raw password. */
    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** The team this owner is registered against. At most one account per team. */
    @Column(nullable = false, unique = true)
    private UUID teamId;

    @Column(nullable = false)
    private Instant createdAt;

    protected UserAccount() {
        // for JPA
    }

    /** Creates a franchise-owner account. {@code passwordHash} must already be BCrypt-encoded. */
    public static UserAccount franchiseOwner(String username, String passwordHash,
                                             String displayName, UUID teamId) {
        UserAccount u = new UserAccount();
        u.id = UUID.randomUUID();
        u.username = username;
        u.passwordHash = passwordHash;
        u.displayName = displayName;
        u.role = Role.FRANCHISE_OWNER;
        u.teamId = teamId;
        u.createdAt = Instant.now();
        return u;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTournamentId() {
        return tournamentId;
    }

    public void setTournamentId(UUID tournamentId) {
        this.tournamentId = tournamentId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
