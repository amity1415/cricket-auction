package com.auctiontracker.dashboard;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerRepository;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import com.auctiontracker.core.TeamRepository;
import com.auctiontracker.dashboard.DashboardViews.DashboardView;
import com.auctiontracker.dashboard.DashboardViews.OnTheBlockView;
import com.auctiontracker.dashboard.DashboardViews.SquadMemberView;
import com.auctiontracker.dashboard.DashboardViews.TeamDetailView;
import com.auctiontracker.dashboard.DashboardViews.TeamSnapshot;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-side projections, computed at request time (ARCHITECTURE.md section 4).
 * Depends only on core plus the bidding facade — never another module's internals.
 */
@Service
public class DashboardService {

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final FeasibilityService feasibility;
    private final BiddingService bidding;

    public DashboardService(PlayerRepository players, TeamRepository teams,
                            FeasibilityService feasibility, BiddingService bidding) {
        this.players = players;
        this.teams = teams;
        this.feasibility = feasibility;
        this.bidding = bidding;
    }

    public DashboardView fullDashboard() {
        // Fetch every squad member ONCE and group by team, then build each team's
        // snapshot from its in-memory list. Previously each snapshot re-queried the
        // squad ~7 times, so a 5-team board fired ~35 round-trips to a remote DB and
        // took seconds; this is a couple of queries total.
        Map<UUID, List<Player>> squadsByTeam = players.findAll().stream()
                .filter(p -> p.getSoldToTeamId() != null)
                .collect(Collectors.groupingBy(Player::getSoldToTeamId));
        List<TeamSnapshot> snapshots = teams.findAll().stream()
                .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
                .map(team -> snapshot(team, squadsByTeam.getOrDefault(team.getTeamId(), List.of())))
                .toList();
        return new DashboardView(onTheBlock(), snapshots, Instant.now());
    }

    public TeamDetailView teamDetail(UUID teamId) {
        Team team = teams.findById(teamId).orElseThrow(() ->
                AuctionException.notFound("TEAM_NOT_FOUND", "No team with id " + teamId));
        List<Player> squadPlayers = players.findBySoldToTeamId(teamId);
        List<SquadMemberView> squad = squadPlayers.stream()
                .sorted(Comparator.comparing(Player::getSoldAt))
                .map(p -> new SquadMemberView(p.getPlayerId(), p.getName(), p.getRole(),
                        p.getCategory(), p.isOverseas(), p.getStatus() == PlayerStatus.RETAINED,
                        p.getSoldPrice(), p.getSoldAt()))
                .toList();
        return new TeamDetailView(snapshot(team, squadPlayers), squad, Instant.now());
    }

    /** Single-team snapshot (fetches this team's squad once). */
    public TeamSnapshot snapshot(Team team) {
        return snapshot(team, players.findBySoldToTeamId(team.getTeamId()));
    }

    /** Snapshot from a pre-fetched squad list — no per-figure queries. */
    public TeamSnapshot snapshot(Team team, List<Player> squad) {
        return new TeamSnapshot(
                team.getTeamId(),
                team.getName(),
                team.getOwnerName(),
                team.getStartingPurse(),
                team.getRemainingPurse(),
                team.squadSize(),
                Math.max(0, team.getMaxSquadSize() - team.squadSize()),
                feasibility.maxAffordableBid(team, squad),
                feasibility.remainingMandatorySlots(team, squad),
                feasibility.overseasCount(squad),
                team.getMaxOverseasPlayers(),
                feasibility.roleCounts(squad),
                team.getMinPerRole(),
                feasibility.categoryCounts(squad));
    }

    private OnTheBlockView onTheBlock() {
        return players.findFirstByStatus(PlayerStatus.UNDER_AUCTION)
                .map(this::onTheBlockView)
                .orElse(null);
    }

    private OnTheBlockView onTheBlockView(Player player) {
        // Live bid state comes from the in-memory session via the bidding facade,
        // not the database — bids only persist once the outcome commits.
        Long currentAmount = bidding.currentBidAmount(player.getPlayerId());
        UUID leadingTeamId = bidding.currentLeadingTeamId(player.getPlayerId());
        String leadingTeamName = leadingTeamId == null ? null
                : teams.findById(leadingTeamId).map(Team::getName).orElse(null);
        return new OnTheBlockView(
                player.getPlayerId(),
                player.getName(),
                player.getRole(),
                player.getCategory(),
                player.getBasePrice(),
                player.isOverseas(),
                player.getStats(),
                currentAmount,
                leadingTeamId,
                leadingTeamName,
                bidding.nextBidAmount(player),
                bidding.bidCount(player.getPlayerId()));
    }
}
