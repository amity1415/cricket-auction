package com.auctiontracker.core;

import jakarta.persistence.Embeddable;

/**
 * Career statistics shown as the player's profile while under auction.
 * Every field is optional — batting-only players leave the bowling fields
 * null and vice versa; the UI hides whatever is null.
 *
 * <p>Two free-text fields, {@link #highestScore()} and {@link #bestBowling()},
 * are strings rather than numbers because they carry cricket notation the numeric
 * types can't hold — a not-out marker ({@code 155*}) and the wickets/runs figure
 * ({@code 5/13}) respectively.
 */
@Embeddable
public record PlayerStats(
        Integer matches,
        // --- Batting ---
        Integer battingInnings,
        Integer runs,
        Double battingAverage,
        Double strikeRate,
        String highestScore,
        // --- Bowling ---
        Integer bowlingInnings,
        Integer wickets,
        Double economyRate,
        Double bowlingAverage,
        String bestBowling) {

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /** Named so it isn't picked up as a JSON property by accident. */
    public boolean allNull() {
        return matches == null && battingInnings == null && runs == null
                && battingAverage == null && strikeRate == null && blank(highestScore)
                && bowlingInnings == null && wickets == null && economyRate == null
                && bowlingAverage == null && blank(bestBowling);
    }
}
