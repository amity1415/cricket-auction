package com.auctiontracker.core;

import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facade of the core module: player/team registration, lookups, bulk import.
 * Other modules call this (or the repository ports), never each other's internals.
 */
@Service
public class CoreService {

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final RuleBook ruleBook;
    private final PlayerRowParser parser;

    public CoreService(PlayerRepository players, TeamRepository teams, RuleBook ruleBook,
                       PlayerRowParser parser) {
        this.players = players;
        this.teams = teams;
        this.ruleBook = ruleBook;
        this.parser = parser;
    }

    @Transactional
    public Player registerPlayer(String name, PlayerRole role, PlayerCategory category,
                                 Long basePriceOverride, PlayerStats stats) {
        if (name == null || name.isBlank()) {
            throw AuctionException.badRequest("INVALID_PLAYER", "Player name must not be blank");
        }
        long basePrice = basePriceOverride != null ? basePriceOverride : ruleBook.current().basePriceFor(category);
        if (basePrice <= 0) {
            throw AuctionException.badRequest("INVALID_PLAYER", "Base price must be positive");
        }
        Player player = Player.register(name.trim(), role, category, basePrice);
        if (stats != null && !stats.allNull()) {
            player.setStats(stats);
        }
        return players.save(player);
    }

    @Transactional
    public Team registerTeam(String name, String ownerName, long startingPurse, int maxSquadSize,
                             Map<PlayerRole, Integer> minPerRole) {
        if (name == null || name.isBlank()) {
            throw AuctionException.badRequest("INVALID_TEAM", "Team name must not be blank");
        }
        if (startingPurse <= 0) {
            throw AuctionException.badRequest("INVALID_TEAM", "Starting purse must be positive");
        }
        if (maxSquadSize < 1) {
            throw AuctionException.badRequest("INVALID_TEAM", "Max squad size must be at least 1");
        }
        int mandatorySlots = minPerRole == null ? 0
                : minPerRole.values().stream().mapToInt(Integer::intValue).sum();
        if (mandatorySlots > maxSquadSize) {
            throw AuctionException.badRequest("INVALID_TEAM",
                    "Role minimums (" + mandatorySlots + ") exceed max squad size (" + maxSquadSize + ")");
        }
        return teams.save(Team.register(name.trim(), ownerName, startingPurse, maxSquadSize,
                minPerRole));
    }

    /**
     * CSV bulk import (DESIGN.md 5.1), append semantics. See {@link PlayerRowParser}
     * for the column layout. All-or-nothing: any bad line rejects the whole file.
     */
    @Transactional
    public List<Player> bulkImportPlayers(String csv) {
        List<Player> parsed = parser.parseCsv(csv);
        parsed.forEach(players::save);
        return parsed;
    }

    /**
     * Replace semantics for the setup page: deletes every player and resets each
     * team's auction state (squad emptied, purse restored) before saving the new
     * pool. Callers wipe bid/sale history first — see the setup module.
     */
    @Transactional
    public List<Player> replaceAllPlayers(List<Player> newPlayers) {
        players.deleteAll();
        teams.findAll().forEach(team -> {
            team.getSquadPlayerIds().clear();
            team.setRemainingPurse(team.getStartingPurse());
            teams.save(team);
        });
        newPlayers.forEach(players::save);
        return newPlayers;
    }

    /**
     * Edits a player's identity/profile. Only AVAILABLE or UNSOLD players can
     * change — anyone retained, on the block, or sold is locked.
     */
    @Transactional
    public Player updatePlayer(UUID playerId, String name, PlayerRole role, PlayerCategory category,
                               Long basePriceOverride, PlayerStats stats) {
        Player player = getPlayer(playerId);
        requireEditable(player, "edited");
        if (name == null || name.isBlank()) {
            throw AuctionException.badRequest("INVALID_PLAYER", "Player name must not be blank");
        }
        long basePrice = basePriceOverride != null ? basePriceOverride : ruleBook.current().basePriceFor(category);
        if (basePrice <= 0) {
            throw AuctionException.badRequest("INVALID_PLAYER", "Base price must be positive");
        }
        player.setName(name.trim());
        player.setRole(role);
        player.setCategory(category);
        player.setBasePrice(basePrice);
        player.setStats(stats != null && !stats.allNull() ? stats : null);
        return players.save(player);
    }

    @Transactional
    public void deletePlayer(UUID playerId) {
        Player player = getPlayer(playerId);
        requireEditable(player, "removed");
        players.deleteById(playerId);
    }

    private void requireEditable(Player player, String action) {
        if (player.getStatus() != PlayerStatus.AVAILABLE && player.getStatus() != PlayerStatus.UNSOLD) {
            throw AuctionException.conflict("INVALID_STATE",
                    "%s is %s — only AVAILABLE or UNSOLD players can be %s"
                            .formatted(player.getName(), player.getStatus(), action));
        }
    }

    /** Edits a team. Purse changes keep the spent amount intact; caps can't drop below use. */
    @Transactional
    public Team updateTeam(UUID teamId, String name, String ownerName,
                           long startingPurse, int maxSquadSize) {
        Team team = getTeam(teamId);
        if (name == null || name.isBlank()) {
            throw AuctionException.badRequest("INVALID_TEAM", "Team name must not be blank");
        }
        if (startingPurse <= 0) {
            throw AuctionException.badRequest("INVALID_TEAM", "Starting purse must be positive");
        }
        long spent = team.getStartingPurse() - team.getRemainingPurse();
        if (startingPurse < spent) {
            throw AuctionException.conflict("INVALID_PURSE",
                    "%s has already spent %s — the starting purse can't go below that"
                            .formatted(team.getName(), Money.inr(spent)));
        }
        if (maxSquadSize < team.squadSize()) {
            throw AuctionException.conflict("SQUAD_TOO_SMALL",
                    "%s already has %d player(s) — max squad size can't go below that"
                            .formatted(team.getName(), team.squadSize()));
        }
        team.setName(name.trim());
        team.setOwnerName(ownerName);
        team.setStartingPurse(startingPurse);
        team.setRemainingPurse(startingPurse - spent);
        team.setMaxSquadSize(maxSquadSize);
        return teams.save(team);
    }

    /** Removes a team — only while its squad is empty (release/re-import first). */
    @Transactional
    public void deleteTeam(UUID teamId) {
        Team team = getTeam(teamId);
        if (team.squadSize() > 0) {
            throw AuctionException.conflict("TEAM_HAS_PLAYERS",
                    "%s still has %d player(s) on its squad — release or reset them before removing the team"
                            .formatted(team.getName(), team.squadSize()));
        }
        teams.deleteById(teamId);
    }

    /** Team name for display, tolerant of deleted teams (e.g. old bid history). */
    public String teamNameOrFallback(UUID teamId) {
        return teams.findById(teamId).map(Team::getName).orElse("(removed team)");
    }

    public Player getPlayer(UUID playerId) {
        return players.findById(playerId).orElseThrow(() ->
                AuctionException.notFound("PLAYER_NOT_FOUND", "No player with id " + playerId));
    }

    public Team getTeam(UUID teamId) {
        return teams.findById(teamId).orElseThrow(() ->
                AuctionException.notFound("TEAM_NOT_FOUND", "No team with id " + teamId));
    }

    public List<Player> listPlayers(PlayerStatus status, PlayerRole role, PlayerCategory category) {
        return players.findAll().stream()
                .filter(p -> status == null || p.getStatus() == status)
                .filter(p -> role == null || p.getRole() == role)
                .filter(p -> category == null || p.getCategory() == category)
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<Team> listTeams() {
        return teams.findAll().stream()
                .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
