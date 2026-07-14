package com.auctiontracker.dashboard;

import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRole;
import com.auctiontracker.core.PlayerStats;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-side view records served by the dashboard module. */
public final class DashboardViews {

    private DashboardViews() {}

    public record TeamSnapshot(
            UUID teamId,
            String name,
            String ownerName,
            long startingPurse,
            long remainingPurse,
            int squadFilled,
            int squadOpenSlots,
            long maxAffordableBid,
            int remainingMandatorySlots,
            Map<PlayerRole, Integer> roleCounts,
            Map<PlayerRole, Integer> minPerRole,
            Map<PlayerCategory, Integer> categoryCounts,
            /** Max this team may bid on the player currently on the block; null when none is. */
            Long maxBidForBlockPlayer) {}

    public record OnTheBlockView(
            UUID playerId,
            String name,
            PlayerRole role,
            PlayerCategory category,
            long basePrice,
            PlayerStats stats,
            Long currentBidAmount,
            UUID currentLeadingTeamId,
            String currentLeadingTeamName,
            long nextBidAmount,
            int bidCount) {}

    public record DashboardView(
            OnTheBlockView onTheBlock,
            List<TeamSnapshot> teams,
            Instant lastUpdated) {}

    public record SquadMemberView(
            UUID playerId,
            String name,
            PlayerRole role,
            PlayerCategory category,
            boolean retained,
            Long soldPrice,
            Instant soldAt) {}

    public record TeamDetailView(
            TeamSnapshot team,
            List<SquadMemberView> squad,
            Instant lastUpdated) {}
}
