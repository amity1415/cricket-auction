package com.auctiontracker.web.dto;

import com.auctiontracker.bidding.BidEvent;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRole;
import com.auctiontracker.core.PlayerStats;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.dashboard.DashboardViews.TeamSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies for the web layer. */
public final class Responses {

    private Responses() {}

    public record PlayerView(
            UUID playerId,
            String name,
            PlayerRole role,
            PlayerCategory category,
            long basePrice,
            PlayerStats stats,
            PlayerStatus status,
            UUID soldToTeamId,
            Long soldPrice,
            Instant soldAt,
            boolean hasPhoto) {

        public static PlayerView from(Player p) {
            return new PlayerView(p.getPlayerId(), p.getName(), p.getRole(), p.getCategory(),
                    p.getBasePrice(), p.getStats(), p.getStatus(),
                    p.getSoldToTeamId(), p.getSoldPrice(), p.getSoldAt(), p.hasPhoto());
        }
    }

    /** Shape follows the DESIGN.md 6.3 success sample. */
    public record PlaceBidResponse(
            UUID playerId,
            String playerName,
            PlayerStatus status,
            long currentBidAmount,
            UUID currentLeadingTeamId,
            String currentLeadingTeamName,
            int bidNumber,
            long nextMinimumIncrement,
            long nextBidAmount) {}

    /** Shape follows the DESIGN.md 6.4 confirm-sale sample: player + affected team snapshot. */
    public record ConfirmSaleResponse(
            PlayerView player,
            List<TeamSnapshot> teams,
            Instant lastUpdated) {}

    public record BidView(
            UUID bidEventId,
            UUID playerId,
            UUID teamId,
            String teamName,
            long amount,
            int bidNumber,
            Instant recordedAt) {

        public static BidView from(BidEvent e, String teamName) {
            return new BidView(e.getBidEventId(), e.getPlayerId(), e.getTeamId(), teamName,
                    e.getAmount(), e.getBidNumber(), e.getRecordedAt());
        }
    }

    /** Live price view for GET /api/players/{id}/current-bid. */
    public record CurrentBidView(
            UUID playerId,
            String name,
            PlayerStatus status,
            long basePrice,
            Long currentBidAmount,
            UUID currentLeadingTeamId,
            String currentLeadingTeamName,
            Long nextBidAmount, // null unless the player is under auction
            int bidCount,
            Instant lastUpdated) {}

    public record BulkImportResponse(int imported, List<PlayerView> players) {}
}
