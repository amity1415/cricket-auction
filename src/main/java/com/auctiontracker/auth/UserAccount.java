package com.auctiontracker.auth;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A DB login. Two kinds live here (the config app-admin does NOT — see
 * {@link SecurityProperties}):
 *  - FRANCHISE_OWNER: tied to one {@code teamId}, read-only.
 *  - TOURNAMENT_ADMIN: no team; granted admin over the auctions in
 *    {@code adminTournamentIds}.
 * Passwords are stored only as a BCrypt hash, never in clear text.
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

    /**
     * The team a franchise owner is registered against (at most one account per
     * team). NULL for tournament admins, who own no team.
     */
    @Column(unique = true)
    private UUID teamId;

    /** Auctions a TOURNAMENT_ADMIN may administer (empty for other roles). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_admin_tournament",
            joinColumns = @JoinColumn(name = "user_account_id"))
    @Column(name = "tournament_id")
    private Set<UUID> adminTournamentIds = new HashSet<>();

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

    /**
     * Creates a tournament-admin account with admin access to the given auctions.
     * {@code passwordHash} must already be BCrypt-encoded.
     */
    public static UserAccount tournamentAdmin(String username, String passwordHash,
                                              String displayName, Set<UUID> tournamentIds) {
        UserAccount u = new UserAccount();
        u.id = UUID.randomUUID();
        u.username = username;
        u.passwordHash = passwordHash;
        u.displayName = displayName;
        u.role = Role.TOURNAMENT_ADMIN;
        u.teamId = null;
        if (tournamentIds != null) {
            u.adminTournamentIds.addAll(tournamentIds);
        }
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

    public Set<UUID> getAdminTournamentIds() {
        return adminTournamentIds;
    }

    public void setAdminTournamentIds(Set<UUID> adminTournamentIds) {
        this.adminTournamentIds = adminTournamentIds == null ? new HashSet<>() : adminTournamentIds;
    }

    /** Whether this account may administer the given auction. */
    public boolean canAdmin(UUID tournamentId) {
        return role == Role.TOURNAMENT_ADMIN && tournamentId != null
                && adminTournamentIds.contains(tournamentId);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
