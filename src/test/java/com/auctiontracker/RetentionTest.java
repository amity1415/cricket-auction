package com.auctiontracker;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.bidding.InMemoryBidEventRepository;
import com.auctiontracker.bidding.IncrementRuleEngine;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.InMemoryPlayerRepository;
import com.auctiontracker.core.InMemoryTeamRepository;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import com.auctiontracker.sale.InMemorySaleRepository;
import com.auctiontracker.sale.Sale;
import com.auctiontracker.sale.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.auctiontracker.core.PlayerCategory.A;
import static com.auctiontracker.core.PlayerCategory.C;
import static com.auctiontracker.core.PlayerCategory.E;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pre-auction retention: 3 per team max — 2 from group A, 1 from the lower groups. */
class RetentionTest {

    private InMemoryPlayerRepository players;
    private InMemoryTeamRepository teams;
    private InMemorySaleRepository sales;
    private BiddingService bidding;
    private SaleService sale;

    @BeforeEach
    void setUp() {
        players = new InMemoryPlayerRepository();
        teams = new InMemoryTeamRepository();
        sales = new InMemorySaleRepository();
        var props = TestFixtures.props();
        var feasibility = new FeasibilityService(players, props);
        var lock = new AuctionLock();
        bidding = new BiddingService(players, teams, new InMemoryBidEventRepository(),
                new IncrementRuleEngine(props), feasibility, lock);
        sale = new SaleService(players, teams, sales, feasibility, lock, props, bidding);
    }

    private Team saveTeam() {
        return teams.save(TestFixtures.team("Chennai Chargers", 150_000_000L, 8, Map.of()));
    }

    private Player savePlayer(String name, PlayerCategory category, long basePrice) {
        return players.save(TestFixtures.player(name, BATSMAN, category, basePrice));
    }

    @Test
    void retainDeductsFlatGroupFeeAndJoinsSquad() {
        Team team = saveTeam();
        Player p = savePlayer("Star", A, 20_000_000L);

        var result = sale.retainPlayer(team.getTeamId(), p.getPlayerId());

        assertEquals(PlayerStatus.RETAINED, p.getStatus());
        assertEquals(team.getTeamId(), p.getSoldToTeamId());
        // RULE 2: group-A retention costs the flat fee (₹12L in fixtures), not base price.
        assertEquals(1_200_000L, p.getSoldPrice());
        assertEquals(148_800_000L, team.getRemainingPurse());
        assertTrue(team.getSquadPlayerIds().contains(p.getPlayerId()));
        assertEquals(Sale.Type.RETAINED, sales.findAllByOrderByRecordedAtAsc().get(0).getType());
        assertEquals(team.getTeamId(), result.team().getTeamId());
    }

    @Test
    void twoFromGroupAAllowedThirdRejected() {
        Team team = saveTeam();
        sale.retainPlayer(team.getTeamId(), savePlayer("A1", A, 20_000_000L).getPlayerId());
        sale.retainPlayer(team.getTeamId(), savePlayer("A2", A, 20_000_000L).getPlayerId());

        var ex = assertThrows(AuctionException.class, () ->
                sale.retainPlayer(team.getTeamId(), savePlayer("A3", A, 20_000_000L).getPlayerId()));
        assertEquals("RETENTION_LIMIT", ex.getCode());
        assertTrue(ex.getMessage().contains("group-A"));
    }

    @Test
    void onlyOneFromLowerGroupsAllowed() {
        Team team = saveTeam();
        sale.retainPlayer(team.getTeamId(), savePlayer("C1", C, 2_000_000L).getPlayerId());

        var ex = assertThrows(AuctionException.class, () ->
                sale.retainPlayer(team.getTeamId(), savePlayer("E1", E, 1_000_000L).getPlayerId()));
        assertEquals("RETENTION_LIMIT", ex.getCode());
        assertTrue(ex.getMessage().contains("lower groups"));
    }

    @Test
    void fullRetentionSetIsTwoAPlusOneLower() {
        Team team = saveTeam();
        sale.retainPlayer(team.getTeamId(), savePlayer("A1", A, 20_000_000L).getPlayerId());
        sale.retainPlayer(team.getTeamId(), savePlayer("A2", A, 20_000_000L).getPlayerId());
        sale.retainPlayer(team.getTeamId(), savePlayer("C1", C, 2_000_000L).getPlayerId());
        // Two group-A (₹12L each) + one lower-group (₹6L) = ₹30L off a ₹15Cr purse.
        assertEquals(147_000_000L, team.getRemainingPurse());

        // Any fourth retention is over the total cap, whatever the group.
        var ex = assertThrows(AuctionException.class, () ->
                sale.retainPlayer(team.getTeamId(), savePlayer("E1", E, 1_000_000L).getPlayerId()));
        assertEquals("RETENTION_LIMIT", ex.getCode());
    }

    @Test
    void releaseRefundsAndReturnsPlayerToPool() {
        Team team = saveTeam();
        Player p = savePlayer("Star", A, 20_000_000L);
        sale.retainPlayer(team.getTeamId(), p.getPlayerId());

        sale.releasePlayer(p.getPlayerId());

        assertEquals(PlayerStatus.AVAILABLE, p.getStatus());
        assertNull(p.getSoldToTeamId());
        assertNull(p.getSoldPrice());
        assertEquals(150_000_000L, team.getRemainingPurse());
        assertTrue(team.getSquadPlayerIds().isEmpty());
        var log = sales.findAllByOrderByRecordedAtAsc();
        assertEquals(Sale.Type.RELEASED, log.get(log.size() - 1).getType());
        // And the released player can be retained again or auctioned normally.
        bidding.markUnderAuction(p.getPlayerId());
        assertEquals(PlayerStatus.UNDER_AUCTION, p.getStatus());
    }

    @Test
    void retainedPlayerCannotGoUnderAuctionOrBeRetainedTwice() {
        Team team = saveTeam();
        Team rival = teams.save(TestFixtures.team("Rival", 150_000_000L, 8, Map.of()));
        Player p = savePlayer("Star", A, 20_000_000L);
        sale.retainPlayer(team.getTeamId(), p.getPlayerId());

        assertEquals("INVALID_STATE",
                assertThrows(AuctionException.class, () -> bidding.markUnderAuction(p.getPlayerId())).getCode());
        assertEquals("INVALID_STATE",
                assertThrows(AuctionException.class, () -> sale.retainPlayer(rival.getTeamId(), p.getPlayerId())).getCode());
    }
}
