package com.auctiontracker.auth;

/**
 * The access levels (DESIGN.md — phase-5 security).
 *  - ADMIN is the single app admin, authenticated from config (not the DB). It
 *    runs everything: create/delete auctions, manage users, and full admin in
 *    every auction.
 *  - TOURNAMENT_ADMIN is a DB account granted admin access to one or more
 *    specific auctions (see {@code UserAccount.adminTournamentIds}). Full admin
 *    WITHIN those auctions, but cannot create/delete auctions or manage users.
 *  - FRANCHISE_OWNER is an account tied to one team; read-only dashboards/team
 *    views, no admin console.
 *
 * Spring Security authorities are the role name prefixed with {@code ROLE_}.
 */
public enum Role {
    ADMIN,
    TOURNAMENT_ADMIN,
    FRANCHISE_OWNER;

    /** The Spring Security authority string for this role (e.g. {@code ROLE_ADMIN}). */
    public String authority() {
        return "ROLE_" + name();
    }
}
