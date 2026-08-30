package com.auctiontracker.web;

import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRepository;
import com.auctiontracker.core.PlayerStats;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import com.auctiontracker.core.TeamRepository;
import com.auctiontracker.tournament.RuleBook;
import com.auctiontracker.tournament.Tournament;
import com.auctiontracker.tournament.TournamentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Post-auction team showcase — the public "final squads" board an organiser can
 * screenshot straight onto Instagram. It is published only once the admin marks
 * the auction complete (see {@code Tournament.auctionComplete}).
 *
 * <p>The read endpoint is mounted under {@code /api/dashboard/**} so it inherits
 * that path's public-read security rule (same trick as the ticker). The admin
 * toggle lives under {@code /api/admin/**} (ADMIN only). Both resolve the current
 * tournament from the request's tournament context (or the active default), so
 * the roster and the completion flag always describe the same auction.
 */
@RestController
public class ShowcaseController {

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final RuleBook ruleBook;
    private final TournamentService tournaments;

    public ShowcaseController(PlayerRepository players, TeamRepository teams,
                              RuleBook ruleBook, TournamentService tournaments) {
        this.players = players;
        this.teams = teams;
        this.ruleBook = ruleBook;
        this.tournaments = tournaments;
    }

    /** One player on a team's showcase card. {@code label} is only set for OWNER/ICON. */
    public record ShowcasePlayer(UUID playerId, String name, String label) {}

    public record ShowcaseTeam(UUID teamId, String name, String ownerName,
                               List<ShowcasePlayer> players) {}

    public record ShowcaseView(boolean complete, String tournamentName,
                               List<ShowcaseTeam> teams, Instant lastUpdated) {}

    public record CompleteRequest(boolean complete) {}

    @GetMapping("/api/dashboard/showcase")
    public ShowcaseView showcase(Authentication authentication) {
        UUID tid = ruleBook.activeTournamentId();
        Tournament tournament = tid == null ? null : tournaments.get(tid);
        boolean complete = tournament != null && tournament.isAuctionComplete();
        String name = tournament == null ? "Auction" : tournament.getName();

        // Withhold the roster until it is published — unless an admin is previewing.
        if (!complete && !isAdmin(authentication)) {
            return new ShowcaseView(false, name, List.of(), Instant.now());
        }

        Map<UUID, List<Player>> squadsByTeam = players.findAll().stream()
                .filter(p -> p.getSoldToTeamId() != null)
                .filter(p -> p.getStatus() == PlayerStatus.SOLD
                        || p.getStatus() == PlayerStatus.RETAINED)
                .collect(Collectors.groupingBy(Player::getSoldToTeamId));

        List<ShowcaseTeam> cards = teams.findAll().stream()
                .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
                .map(team -> new ShowcaseTeam(team.getTeamId(), team.getName(), team.getOwnerName(),
                        orderedRoster(squadsByTeam.getOrDefault(team.getTeamId(), List.of()))))
                .toList();

        return new ShowcaseView(complete, name, cards, Instant.now());
    }

    /** Admin marks the auction finished (or reopens it), publishing/hiding the page. */
    @PostMapping("/api/admin/showcase")
    public ShowcaseView setComplete(@RequestBody CompleteRequest request, Authentication authentication) {
        UUID tid = ruleBook.activeTournamentId();
        if (tid != null) {
            tournaments.setAuctionComplete(tid, request.complete());
        }
        return showcase(authentication);
    }

    /**
     * A spreadsheet of every player with all available data — identity, pool, base
     * price, status, the team they went to and the price, plus career stats — for
     * the current tournament. Served as a downloadable CSV (opens in Excel/Sheets).
     */
    @GetMapping(value = "/api/dashboard/players-export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> playersCsv() {
        Map<UUID, String> teamNames = teams.findAll().stream()
                .collect(Collectors.toMap(Team::getTeamId, Team::getName));

        String[] header = {
                "SL No", "Name", "Role", "Category", "Base Price", "Status",
                "Team", "Sold/Retained Price",
                "Matches", "Bat Inns", "Runs", "Highest Score", "Batting Avg", "Strike Rate",
                "Bowl Inns", "Wickets", "Economy", "Bowling Avg", "Best Bowling",
                "CricHeroes URL"
        };
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');   // BOM so Excel reads UTF-8 names correctly
        appendRow(csv, header);

        players.findAll().stream()
                .sorted(Comparator.comparing((Player p) -> p.getSeq() == null ? Integer.MAX_VALUE : p.getSeq())
                        .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(p -> {
                    PlayerStats s = p.getStats();
                    String team = p.getSoldToTeamId() == null ? "" : teamNames.getOrDefault(p.getSoldToTeamId(), "");
                    appendRow(csv,
                            p.getSeq() == null ? "" : String.valueOf(p.getSeq()),
                            p.getName(),
                            p.getRole() == null ? "" : p.getRole().name(),
                            p.getCategory() == null ? "" : p.getCategory().name(),
                            String.valueOf(p.getBasePrice()),
                            p.getStatus() == null ? "" : p.getStatus().name(),
                            team,
                            p.getSoldPrice() == null ? "" : String.valueOf(p.getSoldPrice()),
                            num(s == null ? null : s.matches()),
                            num(s == null ? null : s.battingInnings()),
                            num(s == null ? null : s.runs()),
                            s == null ? "" : nz(s.highestScore()),
                            num(s == null ? null : s.battingAverage()),
                            num(s == null ? null : s.strikeRate()),
                            num(s == null ? null : s.bowlingInnings()),
                            num(s == null ? null : s.wickets()),
                            num(s == null ? null : s.economyRate()),
                            num(s == null ? null : s.bowlingAverage()),
                            s == null ? "" : nz(s.bestBowling()),
                            nz(p.getCricheroesUrl()));
                });

        UUID tid = ruleBook.activeTournamentId();
        String name = tid == null ? "players" : tournaments.get(tid).getName();
        String file = slug(name) + "-players-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendRow(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(cells[i]));
        }
        sb.append("\r\n");
    }

    /** RFC-4180 quoting: wrap in quotes and double any embedded quotes. */
    private static String csvCell(String v) {
        String s = v == null ? "" : v;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    private static String num(Number n) { return n == null ? "" : n.toString(); }
    private static String nz(String s) { return s == null ? "" : s; }

    private static String slug(String name) {
        String s = name == null ? "players" : name.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "players" : s;
    }

    /**
     * OWNER first, then ICON, then every other (auction-pool) player. Owners/icons
     * carry their label; pool players are shown by name only — their pool/tier is
     * deliberately not surfaced on the showcase.
     */
    private List<ShowcasePlayer> orderedRoster(List<Player> squad) {
        return squad.stream()
                .sorted(Comparator
                        .comparingInt((Player p) -> rank(p.getCategory()))
                        .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(p -> new ShowcasePlayer(p.getPlayerId(), p.getName(), label(p.getCategory())))
                .toList();
    }

    private static int rank(PlayerCategory category) {
        if (category == PlayerCategory.OWNER) return 0;
        if (category == PlayerCategory.ICON) return 1;
        return 2;
    }

    private static String label(PlayerCategory category) {
        if (category == PlayerCategory.OWNER) return "OWNER";
        if (category == PlayerCategory.ICON) return "ICON";
        return null;
    }

    private static boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority a : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
