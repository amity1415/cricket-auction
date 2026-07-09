package com.auctiontracker.core;

import jakarta.persistence.Embeddable;

/**
 * Career statistics shown as the player's profile while under auction.
 * Every field is optional — batting-only players leave the bowling fields
 * null and vice versa; the UI hides whatever is null.
 */
@Embeddable
public record PlayerStats(
        Integer matches,
        Integer runs,
        Double battingAverage,
        Double strikeRate,
        Integer wickets,
        Double economyRate) {

    /** Named so it isn't picked up as a JSON property by accident. */
    public boolean allNull() {
        return matches == null && runs == null && battingAverage == null
                && strikeRate == null && wickets == null && economyRate == null;
    }
}
