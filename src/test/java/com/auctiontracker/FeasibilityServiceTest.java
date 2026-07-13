package com.auctiontracker;

import com.auctiontracker.tournament.RuleBook;

import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.InMemoryPlayerRepository;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static com.auctiontracker.core.PlayerCategory.C;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static com.auctiontracker.core.PlayerRole.BOWLER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DESIGN.md 8.1: maxAffordableBid edge cases — zero mandatory slots,
 * exactly one slot, purse of zero, squad full.
 * Formula (5.7): remainingPurse − (remainingMandatorySlots − 1) × minViablePrice.
 * minViablePrice = 2,000,000 in fixtures.
 */
class FeasibilityServiceTest {

    private InMemoryPlayerRepository players;
    private FeasibilityService feasibility;

    @BeforeEach
    void setUp() {
        players = new InMemoryPlayerRepository();
        feasibility = new FeasibilityService(players, RuleBook.fixed(TestFixtures.props()));
    }

    @Test
    void zeroMandatorySlotsLeftMeansFullPurse() {
        Team team = TestFixtures.team("T", 10_000_000L, 8, Map.of());
        assertEquals(10_000_000L, feasibility.maxAffordableBid(team));
    }

    @Test
    void exactlyOneMandatorySlotMeansFullPurse() {
        Team team = TestFixtures.team("T", 10_000_000L, 8, Map.of(BOWLER, 1));
        assertEquals(10_000_000L, feasibility.maxAffordableBid(team));
    }

    @Test
    void twoMandatorySlotsReserveOneMinimumPrice() {
        Team team = TestFixtures.team("T", 10_000_000L, 8, Map.of(BOWLER, 2));
        assertEquals(8_000_000L, feasibility.maxAffordableBid(team));
    }

    @Test
    void purseOfZeroMeansZero() {
        Team team = TestFixtures.team("T", 1L, 8, Map.of());
        team.setRemainingPurse(0L);
        assertEquals(0L, feasibility.maxAffordableBid(team));
    }

    @Test
    void reserveLargerThanPurseClampsToZero() {
        Team team = TestFixtures.team("T", 3_000_000L, 8, Map.of(BOWLER, 4));
        // reserve = (4−1) × 2M = 6M > 3M purse
        assertEquals(0L, feasibility.maxAffordableBid(team));
    }

    @Test
    void fullSquadMeansZero() {
        Team team = TestFixtures.team("T", 10_000_000L, 1, Map.of());
        Player bought = boughtPlayer(team);
        team.getSquadPlayerIds().add(bought.getPlayerId());
        assertEquals(0L, feasibility.maxAffordableBid(team));
    }

    @Test
    void filledRoleMinimumNoLongerReservesPurse() {
        Team team = TestFixtures.team("T", 10_000_000L, 8, Map.of(BATSMAN, 1, BOWLER, 1));
        Player bought = boughtPlayer(team); // a batsman — fills the BATSMAN minimum
        team.getSquadPlayerIds().add(bought.getPlayerId());
        // one slot left (BOWLER) → reserve (1−1)×2M = 0 → full purse
        assertEquals(10_000_000L, feasibility.maxAffordableBid(team));
        assertEquals(1, feasibility.remainingMandatorySlots(team));
    }

    private Player boughtPlayer(Team team) {
        Player p = TestFixtures.player("Bought", BATSMAN, C, 2_000_000L);
        p.setStatus(PlayerStatus.SOLD);
        p.setSoldToTeamId(team.getTeamId());
        p.setSoldPrice(2_000_000L);
        p.setSoldAt(Instant.now());
        return players.save(p);
    }
}
