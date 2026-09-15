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
            int bidCount,
            boolean hasPhoto,
            /** 1-based lot/serial number (import order); null for rows without a seq. */
            Integer serial,
            /**
             * A voice-called bid amount not yet attributed to a team (the
             * auctioneer said the number before naming who bid). When non-null,
             * every screen shows THIS as the current amount and HIDES the leading
             * team logo until a team is named. Null on the normal committed path.
             */
            Long pendingBidAmount,
            /**
             * For an ONLINE auction with an auto-close timer, the absolute instant
             * this lot will auto-close if no further bid lands. Every screen renders
             * a countdown from it against a server clock, so all devices agree. Null
             * for offline auctions, online auctions with no timer, or once closed.
             */
            Instant biddingDeadline) {}

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
            Instant soldAt,
            boolean hasPhoto) {}

    public record TeamDetailView(
            TeamSnapshot team,
            List<SquadMemberView> squad,
            Instant lastUpdated) {}
}
