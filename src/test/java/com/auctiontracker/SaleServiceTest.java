package com.auctiontracker;

import com.auctiontracker.tournament.RuleBook;

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

import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN.md 8.1: confirm-sale commit — all state changes together; invalid transitions rejected. */
class SaleServiceTest {

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
        var feasibility = new FeasibilityService(players, RuleBook.fixed(props));
        var lock = new AuctionLock();
        bidding = new BiddingService(players, teams, new InMemoryBidEventRepository(),
                new IncrementRuleEngine(RuleBook.fixed(props)), feasibility, lock, RuleBook.fixed(props));
        sale = new SaleService(players, teams, sales, feasibility, lock, RuleBook.fixed(props), bidding);
    }

    private Team saveTeam() {
        return teams.save(TestFixtures.team("Chennai Chargers", 150_000_000L, 8, Map.of()));
    }

    private Player biddedPlayer(Team team) {
        Player p = players.save(TestFixtures.player("Arjun", BATSMAN, B, 5_000_000L));
        bidding.markUnderAuction(p.getPlayerId());
        bidding.placeBid(p.getPlayerId(), team.getTeamId());
        return p;
    }

    @Test
    void confirmSaleCommitsPlayerPurseSquadAndAuditTogether() {
        Team team = saveTeam();
        Player p = biddedPlayer(team);

        var result = sale.confirmSale(p.getPlayerId());

        // Player state
        assertEquals(PlayerStatus.SOLD, p.getStatus());
        assertEquals(team.getTeamId(), p.getSoldToTeamId());
        assertEquals(5_000_000L, p.getSoldPrice());
        assertNotNull(p.getSoldAt());
        assertNull(bidding.currentBidAmount(p.getPlayerId())); // live session closed
        // Purse
        assertEquals(145_000_000L, team.getRemainingPurse());
        // Squad
        assertTrue(team.getSquadPlayerIds().contains(p.getPlayerId()));
        // Audit
        var log = sales.findAllByOrderByRecordedAtAsc();
        assertEquals(1, log.size());
        assertEquals(Sale.Type.SOLD, log.get(0).getType());
        assertEquals(5_000_000L, log.get(0).getAmount());
        assertEquals(team.getTeamId(), result.team().getTeamId());
    }

    @Test
    void confirmSaleWithNoBidsRejected() {
        Player p = players.save(TestFixtures.player("Nobody", BATSMAN, B, 5_000_000L));
        bidding.markUnderAuction(p.getPlayerId());

        var ex = assertThrows(AuctionException.class, () -> sale.confirmSale(p.getPlayerId()));
        assertEquals("NO_BIDS", ex.getCode());
        assertEquals(PlayerStatus.UNDER_AUCTION, p.getStatus());
    }

    @Test
    void doubleConfirmSecondCallGetsCleanAlreadySoldError() {
        // DESIGN.md 8.2: simulated double-click — one succeeds, one clean error, no corruption.
        Team team = saveTeam();
        Player p = biddedPlayer(team);

        sale.confirmSale(p.getPlayerId());
        var ex = assertThrows(AuctionException.class, () -> sale.confirmSale(p.getPlayerId()));

        assertEquals("ALREADY_SOLD", ex.getCode());
        assertEquals(145_000_000L, team.getRemainingPurse()); // deducted exactly once
        assertEquals(1, team.getSquadPlayerIds().size());
        assertEquals(1, sales.findAllByOrderByRecordedAtAsc().size());
    }

    @Test
    void confirmSaleOnAvailablePlayerRejected() {
        Player p = players.save(TestFixtures.player("Idle", BATSMAN, B, 5_000_000L));

        var ex = assertThrows(AuctionException.class, () -> sale.confirmSale(p.getPlayerId()));
        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    void markUnsoldUnderAuctionDemotesOneGroupAndReturnsToPool() {
        // Demotion rule: unsold group-B player drops to C, takes C's base price,
        // and is AVAILABLE again for re-auction. Bid state cleared, no purse impact.
        Team team = saveTeam();
        Player p = biddedPlayer(team); // group B, base ₹50L

        sale.markUnsold(p.getPlayerId());

        assertEquals(PlayerStatus.AVAILABLE, p.getStatus());
        assertEquals(PlayerCategory.C, p.getCategory());
        assertEquals(2_000_000L, p.getBasePrice()); // group C base price from config
        assertNull(bidding.currentBidAmount(p.getPlayerId())); // live session closed
        assertEquals(150_000_000L, team.getRemainingPurse()); // no purse impact
        var log = sales.findAllByOrderByRecordedAtAsc();
        assertEquals(1, log.size());
        assertEquals(Sale.Type.UNSOLD, log.get(0).getType());
        assertNull(log.get(0).getTeamId());
        assertNull(log.get(0).getAmount());

        // And the demoted player can genuinely be re-auctioned.
        bidding.markUnderAuction(p.getPlayerId());
        assertEquals(PlayerStatus.UNDER_AUCTION, p.getStatus());
    }

    @Test
    void markUnsoldAtLowestGroupStaysAvailableForReauction() {
        Player p = players.save(TestFixtures.player("Fringe", BATSMAN, PlayerCategory.E, 1_000_000L));
        bidding.markUnderAuction(p.getPlayerId());

        sale.markUnsold(p.getPlayerId());

        // Group E is a sticky floor: unsold during auction returns to the pool so
        // the player can be put on the block again — the sell option never goes away.
        assertEquals(PlayerStatus.AVAILABLE, p.getStatus());
        assertEquals(PlayerCategory.E, p.getCategory());
        // And it can indeed go back under the block.
        bidding.markUnderAuction(p.getPlayerId());
        assertEquals(PlayerStatus.UNDER_AUCTION, p.getStatus());
    }

    @Test
    void markUnsoldDirectlyFromAvailableIsAllowed() {
        // Player withdrawn pre-auction (DESIGN.md section 7 note) — terminal, never demoted.
        Player p = players.save(TestFixtures.player("Withdrawn", BATSMAN, B, 5_000_000L));

        sale.markUnsold(p.getPlayerId());

        assertEquals(PlayerStatus.UNSOLD, p.getStatus());
        assertEquals(B, p.getCategory()); // withdrawal is not an auction failure
        assertEquals(1, sales.findAllByOrderByRecordedAtAsc().size());
    }

    @Test
    void markUnsoldOnSoldPlayerRejected() {
        Team team = saveTeam();
        Player p = biddedPlayer(team);
        sale.confirmSale(p.getPlayerId());

        var ex = assertThrows(AuctionException.class, () -> sale.markUnsold(p.getPlayerId()));
        assertEquals("INVALID_STATE", ex.getCode());
        assertEquals(PlayerStatus.SOLD, p.getStatus());
    }
}
