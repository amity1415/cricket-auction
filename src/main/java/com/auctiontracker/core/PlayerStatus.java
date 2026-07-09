package com.auctiontracker.core;

/**
 * See DESIGN.md section 7 for the full state machine. SOLD and UNSOLD are
 * terminal. RETAINED is a pre-auction state: the player sits on a team's
 * squad at base price and can be released back to AVAILABLE any time before
 * being auctioned.
 */
public enum PlayerStatus {
    AVAILABLE,
    UNDER_AUCTION,
    RETAINED,
    SOLD,
    UNSOLD
}
