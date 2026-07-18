package com.auctiontracker.core;

/**
 * Player group / auction pool. Amounts and per-team min/max quotas live in
 * {@code auction.*} config (per tournament).
 *
 * <p>Two families of groups coexist, selected per tournament by its rule book —
 * neither is mandatory for a given tournament, and both are just enum values so
 * they persist and configure identically:
 * <ul>
 *   <li><b>A–E</b> — the original STANDARD_POOL tiers. Unsold players demote to
 *       the next tier ({@link #nextLower()}, A→B→…→E); E is the floor.</li>
 *   <li><b>MIXED_UTILITY_BAG … MARKEE_PLAYER</b> — the role-based cascade format.
 *       These do NOT use ordinal demotion; where an unsold player goes next is
 *       configured explicitly as a transition graph in the tournament's rules
 *       (see {@code AuctionProperties.unsoldTransitions}).</li>
 * </ul>
 * Adding these values is backward compatible: existing A–E data stays valid and
 * A–E tournaments never reference the role-based groups.
 */
public enum PlayerCategory {
    A, B, C, D, E,

    // Role-based cascade groups (ROLE_BASED_CASCADE format). Display names live
    // in the UI / config; movement between them is config-driven, not ordinal.
    MIXED_UTILITY_BAG,
    WICKET_KEEPER,
    BOWLER,
    ALL_ROUNDER,
    MARKEE_PLAYER;

    /**
     * The next lower A–E tier for the legacy linear demotion, or null. Only the
     * A–E family cascades this way; the role-based groups return null here (their
     * movement is driven by the configured transition graph instead), so E stays
     * the STANDARD_POOL floor exactly as before.
     */
    public PlayerCategory nextLower() {
        return switch (this) {
            case A -> B;
            case B -> C;
            case C -> D;
            case D -> E;
            default -> null;
        };
    }
}
