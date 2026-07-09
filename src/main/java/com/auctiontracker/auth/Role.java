package com.auctiontracker.auth;

/**
 * The two access levels (DESIGN.md — phase-5 security).
 *  - ADMIN runs the auction: setup, live bidding console, and user management.
 *    There is exactly one admin, authenticated from config (not the DB).
 *  - FRANCHISE_OWNER is a self-registered account tied to one team; read-only
 *    access to dashboards and team views, no access to the admin console.
 *
 * Spring Security authorities are the role name prefixed with {@code ROLE_}.
 */
public enum Role {
    ADMIN,
    FRANCHISE_OWNER;

    /** The Spring Security authority string for this role (e.g. {@code ROLE_ADMIN}). */
    public String authority() {
        return "ROLE_" + name();
    }
}
