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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reverting a completed sale: purse refunded, squad slot freed, audit + bid trail wiped. */
class RevertSaleTest {

    private InMemoryPlayerRepository players;
    private InMemoryTeamRepository teams;
    private InMemorySaleRepository sales;
    private InMemoryBidEventRepository bidEvents;
    private BiddingService bidding;
    private SaleService sale;

    @BeforeEach
    void setUp() {
        players = new InMemoryPlayerRepository();
        teams = new InMemoryTeamRepository();
        sales = new InMemorySaleRepository();
        bidEvents = new InMemoryBidEventRepository();
        var props = TestFixtures.props();
        var feasibility = new FeasibilityService(players, RuleBook.fixed(props));
        var lock = new AuctionLock();
        bidding = new BiddingService(players, teams, bidEvents,
                new IncrementRuleEngine(RuleBook.fixed(props)), feasibility, lock, RuleBook.fixed(props));
        sale = new SaleService(players, teams, sales, feasibility, lock, RuleBook.fixed(props), bidding);
    }

    /** Puts a player on the block, takes one bid, and confirms the sale. */
    private Player sellPlayer(Team team, long basePrice) {
        Player p = players.save(TestFixtures.player("Arjun", BATSMAN, B, basePrice));
        bidding.markUnderAuction(p.getPlayerId());
        bidding.placeBid(p.getPlayerId(), team.getTeamId());
        sale.confirmSale(p.getPlayerId());
        return p;
    }

    @Test
    void revertRefundsPurseFreesSquadAndClearsSaleFields() {
        Team team = teams.save(TestFixtures.team("Chennai", 150_000_000L, 8, Map.of()));
        Player p = sellPlayer(team, 5_000_000L);

        // Precondition: sold, purse debited, on the squad, bid trail + SOLD audit persisted.
        assertEquals(PlayerStatus.SOLD, p.getStatus());
        assertEquals(145_000_000L, team.getRemainingPurse());
        assertTrue(team.getSquadPlayerIds().contains(p.getPlayerId()));
        assertEquals(1, bidEvents.countByPlayerId(p.getPlayerId()));

        sale.revertSale(p.getPlayerId());

        assertEquals(PlayerStatus.AVAILABLE, p.getStatus());
        assertNull(p.getSoldToTeamId());
        assertNull(p.getSoldPrice());
        assertNull(p.getSoldAt());
        assertEquals(150_000_000L, team.getRemainingPurse());   // fully refunded
        assertFalse(team.getSquadPlayerIds().contains(p.getPlayerId()));
        assertEquals(0, bidEvents.countByPlayerId(p.getPlayerId())); // void trail wiped

        // Audit: the SOLD row is gone, a RELEASED breadcrumb remains.
        var log = sales.findAllByOrderByRecordedAtAsc();
        assertTrue(log.stream().noneMatch(s -> s.getType() == Sale.Type.SOLD));
        assertEquals(Sale.Type.RELEASED, log.get(log.size() - 1).getType());
    }

    @Test
    void revertedPlayerCanBeAuctionedAgainFromBasePrice() {
        Team team = teams.save(TestFixtures.team("Chennai", 150_000_000L, 8, Map.of()));
        Player p = sellPlayer(team, 5_000_000L);
        sale.revertSale(p.getPlayerId());

        bidding.markUnderAuction(p.getPlayerId());
        var bid = bidding.placeBid(p.getPlayerId(), team.getTeamId());
        assertEquals(5_000_000L, bid.amount());   // fresh auction, back at base price
        assertEquals(1, bidding.bidCount(p.getPlayerId()));
    }

    @Test
    void onlySoldPlayersCanBeReverted() {
        Player p = players.save(TestFixtures.player("Idle", BATSMAN, B, 5_000_000L));
        var ex = assertThrows(AuctionException.class, () -> sale.revertSale(p.getPlayerId()));
        assertEquals("INVALID_STATE", ex.getCode());
    }
}
