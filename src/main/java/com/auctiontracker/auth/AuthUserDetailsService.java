package com.auctiontracker.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Resolves a username to a user for authentication. The admin comes from config
 * ({@link SecurityProperties}); every other user is a franchise owner in the DB.
 * The admin's config password is BCrypt-encoded once at startup so it is matched
 * with the same encoder as DB passwords.
 */
@Service
public class AuthUserDetailsService implements UserDetailsService {

    private final UserAccountRepository accounts;
    private final String adminUsername;
    private final String adminPasswordHash;

    public AuthUserDetailsService(UserAccountRepository accounts,
                                  SecurityProperties props,
                                  PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.adminUsername = props.adminUsername();
        this.adminPasswordHash = passwordEncoder.encode(props.adminPassword());
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        if (adminUsername.equalsIgnoreCase(username)) {
            return AuthPrincipal.admin(adminUsername, adminPasswordHash);
        }
        return accounts.findByUsername(username)
                .map(AuthPrincipal::of)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
    }
}
