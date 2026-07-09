package com.auctiontracker.core;

/**
 * Player group (base-price tier). Amounts and per-team min/max quotas live in
 * {@code auction.*} config. Declaration order matters: an unsold player is
 * demoted to the next enum value (A→B→…→E); the last group can't drop further.
 */
public enum PlayerCategory {
    A, B, C, D, E;

    /** The next lower group, or null if this is already the lowest. */
    public PlayerCategory nextLower() {
        int next = ordinal() + 1;
        PlayerCategory[] all = values();
        return next < all.length ? all[next] : null;
    }
}
