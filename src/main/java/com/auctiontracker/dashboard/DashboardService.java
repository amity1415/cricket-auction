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
        Player blockPlayer = blockPlayer();
        List<TeamSnapshot> snapshots = teams.findAll().stream()
                .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
                .map(team -> snapshot(team, squadsByTeam.getOrDefault(team.getTeamId(), List.of()), blockPlayer))
                .toList();
        return new DashboardView(blockPlayer == null ? null : onTheBlockView(blockPlayer),
                snapshots, Instant.now());
    }

    public TeamDetailView teamDetail(UUID teamId) {
        Team team = teams.findById(teamId).orElseThrow(() ->
                AuctionException.notFound("TEAM_NOT_FOUND", "No team with id " + teamId));
        List<Player> squadPlayers = players.findBySoldToTeamId(teamId);
        List<SquadMemberView> squad = squadPlayers.stream()
                .sorted(Comparator.comparing(Player::getSoldAt))
                .map(p -> new SquadMemberView(p.getPlayerId(), p.getName(), p.getRole(),
                        p.getCategory(), p.getStatus() == PlayerStatus.RETAINED,
                        p.getSoldPrice(), p.getSoldAt(), p.hasPhoto()))
                .toList();
        return new TeamDetailView(snapshot(team, squadPlayers, blockPlayer()), squad, Instant.now());
    }

    /** Single-team snapshot (fetches this team's squad once). */
    public TeamSnapshot snapshot(Team team) {
        return snapshot(team, players.findBySoldToTeamId(team.getTeamId()), blockPlayer());
    }

    /**
     * Snapshot from a pre-fetched squad list — no per-figure queries. When a
     * player is on the block, {@code blockPlayer} is passed so the team's
     * player-specific max next bid can be computed (null otherwise).
     */
    public TeamSnapshot snapshot(Team team, List<Player> squad, Player blockPlayer) {
        Long maxBidForBlock = blockPlayer == null ? null
                : feasibility.maxBidFor(team, blockPlayer, squad);
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
                feasibility.roleCounts(squad),
                team.getMinPerRole(),
                feasibility.categoryCounts(squad),
                maxBidForBlock);
    }

    // --- Ticker live-view cache -------------------------------------------------
    // The broadcast ticker polls onTheBlock() several times a second. The current
    // bid, leading team and current player id all live in server memory (the bidding
    // session); the only DB reads are the on-block player's DISPLAY fields and the
    // leading team's NAME — and neither changes while a player is on the block. So we
    // cache both and serve steady-state polls with ZERO DB round-trips, which makes
    // the current bid update instantly regardless of DB latency and removes the
    // ticker's polling load from the database entirely.
    private record CachedBlock(UUID playerId, Player player) {}
    private volatile CachedBlock cachedBlock;
    private final java.util.Map<UUID, String> teamNameCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Just the on-the-block state (or null). Served from memory during live bidding:
     * the player id comes from the in-memory session, the player's display fields and
     * the team name are cached (loaded once when a new player comes on the block), and
     * the bid figures come from the bidding facade. A DB read happens only on a cache
     * miss — a new player on the block, or after a restart before the session re-opens.
     */
    public OnTheBlockView onTheBlock() {
        UUID blockId = bidding.currentBlockPlayerId();      // in-memory; null when nothing is live
        CachedBlock cached = cachedBlock;
        Player player;
        if (blockId != null && cached != null && blockId.equals(cached.playerId())) {
            player = cached.player();                        // steady state — no DB
        } else {
            // Cache miss: load the player once. Prefer the in-memory id; fall back to
            // the status query when the session is empty (e.g. just after a restart).
            player = blockId != null
                    ? players.findById(blockId).orElse(null)
                    : blockPlayer();
            if (player == null) { cachedBlock = null; teamNameCache.clear(); return null; }
            cachedBlock = new CachedBlock(player.getPlayerId(), player);
            teamNameCache.clear();                           // new player → drop any stale team names
        }
        return onTheBlockView(player);
    }

    /** The player currently under auction, if any. */
    private Player blockPlayer() {
        return players.findFirstByStatus(PlayerStatus.UNDER_AUCTION).orElse(null);
    }

    private OnTheBlockView onTheBlockView(Player player) {
        // Live bid state comes from the in-memory session via the bidding facade,
        // not the database — bids only persist once the outcome commits. The leading
        // team's name is cached (see teamNameCache) so repeated polls don't re-query.
        Long currentAmount = bidding.currentBidAmount(player.getPlayerId());
        UUID leadingTeamId = bidding.currentLeadingTeamId(player.getPlayerId());
        String leadingTeamName = leadingTeamId == null ? null
                : teamNameCache.computeIfAbsent(leadingTeamId,
                        id -> teams.findById(id).map(Team::getName).orElse(null));
        return new OnTheBlockView(
                player.getPlayerId(),
                player.getName(),
                player.getRole(),
                player.getCategory(),
                player.getBasePrice(),
                player.getStats(),
                currentAmount,
                leadingTeamId,
                leadingTeamName,
                bidding.nextBidAmount(player),
                bidding.bidCount(player.getPlayerId()),
                player.hasPhoto(),
                player.getSeq() == null ? null : player.getSeq() + 1);
    }
}
