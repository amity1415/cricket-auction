package com.auctiontracker.web.dto;

import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRole;
import com.auctiontracker.core.PlayerStats;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;
import java.util.UUID;

/** Request bodies for the admin write API. */
public final class Requests {

    private Requests() {}

    public record RegisterPlayerRequest(
            @NotBlank String name,
            @NotNull PlayerRole role,
            @NotNull PlayerCategory category,
            @Positive Long basePrice, // optional — defaults to the category's configured base price
            PlayerStats stats) {} // optional career profile

    public record RegisterTeamRequest(
            @NotBlank String name,
            @NotBlank String ownerName,
            @Positive long startingPurse,
            @Min(1) int maxSquadSize,
            Map<PlayerRole, Integer> minPerRole) {} // optional

    public record UpdateTeamRequest(
            @NotBlank String name,
            @NotBlank String ownerName,
            @Positive long startingPurse,
            @Min(1) int maxSquadSize) {}

    /**
     * A bid for a team. {@code amount} is normally null — the server computes the
     * next increment (DESIGN.md 5.3). It is set only for a manual/floor bid the
     * auctioneer types in: any amount that beats the current bid and the team can
     * afford, off the increment ladder if need be.
     */
    public record PlaceBidRequest(@NotNull UUID teamId, Long amount) {}
}
