package com.auctiontracker.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single admin credential, supplied by config (application.yml / env), NOT
 * the database. The password is given in clear text in config and BCrypt-encoded
 * in memory at startup — set a real one in production via the ADMIN_PASSWORD env
 * var. See application.yml {@code auction.security}.
 */
@ConfigurationProperties(prefix = "auction.security")
public record SecurityProperties(String adminUsername, String adminPassword) {
}
