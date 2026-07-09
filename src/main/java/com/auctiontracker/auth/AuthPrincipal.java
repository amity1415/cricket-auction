package com.auctiontracker.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated user behind a session. Carries the role, display name and
 * (for franchise owners) the owned team id, so endpoints like {@code /api/auth/me}
 * and the post-login redirect can act on them without a second DB lookup.
 */
public class AuthPrincipal implements UserDetails {

    private final UUID userId;      // null for the config-based admin
    private final String username;
    private final String passwordHash;
    private final String displayName;
    private final Role role;
    private final UUID teamId;      // null for the admin

    public AuthPrincipal(UUID userId, String username, String passwordHash,
                         String displayName, Role role, UUID teamId) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.teamId = teamId;
    }

    static AuthPrincipal admin(String username, String passwordHash) {
        return new AuthPrincipal(null, username, passwordHash, "Administrator", Role.ADMIN, null);
    }

    static AuthPrincipal of(UserAccount account) {
        return new AuthPrincipal(account.getId(), account.getUsername(), account.getPasswordHash(),
                account.getDisplayName(), account.getRole(), account.getTeamId());
    }

    public UUID userId() {
        return userId;
    }

    public String displayName() {
        return displayName;
    }

    public Role role() {
        return role;
    }

    public UUID teamId() {
        return teamId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
