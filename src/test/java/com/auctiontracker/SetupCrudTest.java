package com.auctiontracker;

import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.InMemoryPlayerRepository;
import com.auctiontracker.core.InMemoryTeamRepository;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerRowParser;
import com.auctiontracker.core.PlayerStats;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerCategory.C;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static com.auctiontracker.core.PlayerRole.BOWLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Setup-screen CRUD: team edit/remove and player edit/remove with their guards. */
class SetupCrudTest {

    private InMemoryPlayerRepository players;
    private InMemoryTeamRepository teams;
    private CoreService core;

    @BeforeEach
    void setUp() {
        players = new InMemoryPlayerRepository();
        teams = new InMemoryTeamRepository();
        var props = TestFixtures.props();
        core = new CoreService(players, teams, props, new PlayerRowParser(props));
    }

    @Test
    void updateTeamPreservesSpentAmountWhenPurseChanges() {
        Team team = teams.save(TestFixtures.team("Old Name", 100_000_000L, 8, Map.of(), 0));
        team.setRemainingPurse(70_000_000L); // spent 30M

        core.updateTeam(team.getTeamId(), "New Name", "New Owner", 120_000_000L, 10);

        assertEquals("New Name", team.getName());
        assertEquals(120_000_000L, team.getStartingPurse());
        assertEquals(90_000_000L, team.getRemainingPurse()); // 120M − 30M spent
        assertEquals(10, team.getMaxSquadSize());
    }

    @Test
    void purseCannotDropBelowSpent() {
        Team team = teams.save(TestFixtures.team("Spenders", 100_000_000L, 8, Map.of(), 0));
        team.setRemainingPurse(40_000_000L); // spent 60M

        var ex = assertThrows(AuctionException.class, () ->
                core.updateTeam(team.getTeamId(), "Spenders", "O", 50_000_000L, 8));
        assertEquals("INVALID_PURSE", ex.getCode());
    }

    @Test
    void squadCapCannotDropBelowCurrentSquad() {
        Team team = teams.save(TestFixtures.team("Full", 100_000_000L, 8, Map.of(), 0));
        team.getSquadPlayerIds().add(java.util.UUID.randomUUID());
        team.getSquadPlayerIds().add(java.util.UUID.randomUUID());

        var ex = assertThrows(AuctionException.class, () ->
                core.updateTeam(team.getTeamId(), "Full", "O", 100_000_000L, 1));
        assertEquals("SQUAD_TOO_SMALL", ex.getCode());
    }

    @Test
    void deleteTeamBlockedWhileSquadNonEmpty() {
        Team team = teams.save(TestFixtures.team("Keepers", 100_000_000L, 8, Map.of(), 0));
        team.getSquadPlayerIds().add(java.util.UUID.randomUUID());

        var ex = assertThrows(AuctionException.class, () -> core.deleteTeam(team.getTeamId()));
        assertEquals("TEAM_HAS_PLAYERS", ex.getCode());

        team.getSquadPlayerIds().clear();
        core.deleteTeam(team.getTeamId());
        assertEquals(0, teams.count());
    }

    @Test
    void updatePlayerChangesProfileAndRecomputesBasePriceFromGroup() {
        Player p = players.save(TestFixtures.player("Typo Naem", BATSMAN, B, 5_000_000L, false));

        core.updatePlayer(p.getPlayerId(), "Fixed Name", BOWLER, C, null, true,
                new PlayerStats(50, 100, 8.0, 80.0, 60, 7.5));

        assertEquals("Fixed Name", p.getName());
        assertEquals(BOWLER, p.getRole());
        assertEquals(C, p.getCategory());
        assertEquals(2_000_000L, p.getBasePrice()); // group C default from config
        assertEquals(60, p.getStats().wickets());
    }

    @Test
    void editAndDeleteLockedOnceInAuctionFlow() {
        Player p = players.save(TestFixtures.player("Locked", BATSMAN, B, 5_000_000L, false));
        p.setStatus(PlayerStatus.SOLD);

        assertEquals("INVALID_STATE", assertThrows(AuctionException.class, () ->
                core.updatePlayer(p.getPlayerId(), "X", BATSMAN, B, null, false, null)).getCode());
        assertEquals("INVALID_STATE", assertThrows(AuctionException.class, () ->
                core.deletePlayer(p.getPlayerId())).getCode());
    }

    @Test
    void deletePlayerRemovesFromPool() {
        Player p = players.save(TestFixtures.player("Gone", BATSMAN, B, 5_000_000L, false));
        core.deletePlayer(p.getPlayerId());
        assertEquals(0, players.count());
        assertNull(players.findById(p.getPlayerId()).orElse(null));
    }
}
