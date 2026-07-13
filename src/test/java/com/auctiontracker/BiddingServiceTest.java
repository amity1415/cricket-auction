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
import com.auctiontracker.sale.SaleService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN.md 8.1: place-bid validation — each rejection case as a separate test. */
class BiddingServiceTest {

    private InMemoryPlayerRepository players;
    private InMemoryTeamRepository teams;
    private InMemoryBidEventRepository bidEvents;
    private BiddingService bidding;
    private SaleService sale;

    @BeforeEach
    void setUp() {
        players = new InMemoryPlayerRepository();
        teams = new InMemoryTeamRepository();
        bidEvents = new InMemoryBidEventRepository();
        var props = TestFixtures.props();
        var feasibility = new FeasibilityService(players, RuleBook.fixed(props));
        var lock = new AuctionLock();
        bidding = new BiddingService(players, teams, bidEvents,
                new IncrementRuleEngine(RuleBook.fixed(props)), feasibility, lock, RuleBook.fixed(props));
        sale = new SaleService(players, teams, new InMemorySaleRepository(), feasibility, lock, RuleBook.fixed(props), bidding);
    }

    private Team saveTeam(long purse) {
        return teams.save(TestFixtures.team("Chennai Chargers", purse, 8, Map.of()));
    }

    private Player saveUnderAuction(long basePrice) {
        Player p = players.save(TestFixtures.player("Arjun", BATSMAN, B, basePrice));
        bidding.markUnderAuction(p.getPlayerId());
        return p;
    }

    @Test
    void firstBidStartsAtBasePrice() {
        Team team = saveTeam(150_000_000L);
        Player p = saveUnderAuction(5_000_000L);

        var result = bidding.placeBid(p.getPlayerId(), team.getTeamId());

        assertEquals(5_000_000L, result.amount());
        assertEquals(team.getTeamId(), result.leadingTeam().getTeamId());
        assertEquals(1, result.bidNumber());
        assertEquals(1, bidding.bidCount(p.getPlayerId()));
        // Live bids are cache-only: nothing lands in the DB until the outcome commits.
        assertEquals(0, bidEvents.countByPlayerId(p.getPlayerId()));
        // Placing a bid is a quote, not a commitment — purse untouched.
        assertEquals(150_000_000L, team.getRemainingPurse());
    }

    @Test
    void subsequentBidAddsCurrentBandIncrement() {
        Team t1 = saveTeam(150_000_000L);
        Team t2 = teams.save(TestFixtures.team("Mumbai Mavericks", 150_000_000L, 8, Map.of()));
        Player p = saveUnderAuction(5_000_000L);

        bidding.placeBid(p.getPlayerId(), t1.getTeamId());
        var second = bidding.placeBid(p.getPlayerId(), t2.getTeamId());

        assertEquals(6_000_000L, second.amount()); // 5M + 1M band step
        assertEquals(2, second.bidNumber());
    }

    @Test
    void selfOutbidRejected() {
        Team team = saveTeam(150_000_000L);
        Player p = saveUnderAuction(5_000_000L);
        bidding.placeBid(p.getPlayerId(), team.getTeamId());

        var ex = assertThrows(AuctionException.class,
                () -> bidding.placeBid(p.getPlayerId(), team.getTeamId()));
        assertEquals("SELF_OUTBID", ex.getCode());
    }

    @Test
    void bidOnPlayerNotUnderAuctionRejected() {
        Team team = saveTeam(150_000_000L);
        Player p = players.save(TestFixtures.player("Idle", BATSMAN, B, 5_000_000L));

        var ex = assertThrows(AuctionException.class,
                () -> bidding.placeBid(p.getPlayerId(), team.getTeamId()));
        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    void bidOnSoldPlayerRejected() {
        Team team = saveTeam(150_000_000L);
        Player p = saveUnderAuction(5_000_000L);
        bidding.placeBid(p.getPlayerId(), team.getTeamId());
        sale.confirmSale(p.getPlayerId());

        var ex = assertThrows(AuctionException.class,
                () -> bidding.placeBid(p.getPlayerId(), team.getTeamId()));
        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    void insufficientPurseRejectedWithActionableMessage() {
        Team team = saveTeam(4_000_000L);
        Player p = saveUnderAuction(5_000_000L);

        var ex = assertThrows(AuctionException.class,
                () -> bidding.placeBid(p.getPlayerId(), team.getTeamId()));
        assertEquals("INSUFFICIENT_PURSE", ex.getCode());
        assertTrue(ex.getMessage().contains("Chennai Chargers"));
        assertEquals(5_000_000L, ex.getDetails().get("attemptedAmount"));
        assertEquals(4_000_000L, ex.getDetails().get("remainingPurse"));
    }

    @Test
    void squadFullRejected() {
        Team team = teams.save(TestFixtures.team("Tiny", 150_000_000L, 1, Map.of()));
        Player first = saveUnderAuction(2_000_000L);
        bidding.placeBid(first.getPlayerId(), team.getTeamId());
        sale.confirmSale(first.getPlayerId()); // squad now 1/1

        Player second = players.save(TestFixtures.player("Next", BATSMAN, C, 2_000_000L));
        bidding.markUnderAuction(second.getPlayerId());

        var ex = assertThrows(AuctionException.class,
                () -> bidding.placeBid(second.getPlayerId(), team.getTeamId()));
        assertEquals("SQUAD_FULL", ex.getCode());
    }

    @Test
    void markUnderAuctionDisplacesPreviousPlayer() {
        Team team = saveTeam(150_000_000L);
        Player first = saveUnderAuction(5_000_000L);
        bidding.placeBid(first.getPlayerId(), team.getTeamId());

        Player second = players.save(TestFixtures.player("Next", BATSMAN, B, 5_000_000L));
        bidding.markUnderAuction(second.getPlayerId());

        assertEquals(PlayerStatus.AVAILABLE, first.getStatus());
        assertNull(bidding.currentBidAmount(first.getPlayerId())); // live bids discarded
        assertEquals(0, bidding.bidCount(first.getPlayerId()));
        assertEquals(PlayerStatus.UNDER_AUCTION, second.getStatus());
    }

    @Test
    void markUnderAuctionOnSoldPlayerRejected() {
        Team team = saveTeam(150_000_000L);
        Player p = saveUnderAuction(5_000_000L);
        bidding.placeBid(p.getPlayerId(), team.getTeamId());
        sale.confirmSale(p.getPlayerId());

        var ex = assertThrows(AuctionException.class,
                () -> bidding.markUnderAuction(p.getPlayerId()));
        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    void rapidFireBiddingKeepsPriceAndCountConsistent() {
        // DESIGN.md 8.4: 10+ bids in quick succession — final price and count must match.
        Team t1 = saveTeam(150_000_000L);
        Team t2 = teams.save(TestFixtures.team("Rival", 150_000_000L, 8, Map.of()));
        Player p = saveUnderAuction(2_000_000L); // category C base

        for (int i = 0; i < 12; i++) {
            bidding.placeBid(p.getPlayerId(), (i % 2 == 0 ? t1 : t2).getTeamId());
        }

        // bid1=2M (base); bid2: 2M≤2M → +0.5M = 2.5M; bids 3–10: ≤10M → +1M each
        // (3.5, 4.5, 5.5, 6.5, 7.5, 8.5, 9.5, 10.5); bids 11–12: >10M band → +2M each (12.5, 14.5)
        assertEquals(12, bidding.bidCount(p.getPlayerId()));
        assertEquals(14_500_000L, bidding.currentBidAmount(p.getPlayerId()));
        assertEquals(0, bidEvents.countByPlayerId(p.getPlayerId())); // still cache-only
    }

    @Test
    void undoBidPopsLastBidAndReopensThePrice() {
        Team t1 = saveTeam(150_000_000L);
        Team t2 = teams.save(TestFixtures.team("Rival", 150_000_000L, 8, Map.of()));
        Player p = saveUnderAuction(5_000_000L);
        bidding.placeBid(p.getPlayerId(), t1.getTeamId()); // 5M
        bidding.placeBid(p.getPlayerId(), t2.getTeamId()); // 6M

        bidding.undoBid(p.getPlayerId());

        assertEquals(5_000_000L, bidding.currentBidAmount(p.getPlayerId()));
        assertEquals(t1.getTeamId(), bidding.currentLeadingTeamId(p.getPlayerId()));
        assertEquals(1, bidding.bidCount(p.getPlayerId()));
        // t2 can re-bid at the same recomputed price.
        assertEquals(6_000_000L, bidding.placeBid(p.getPlayerId(), t2.getTeamId()).amount());
    }

    @Test
    void undoBidBackToZeroThenNothingLeftToUndo() {
        Team team = saveTeam(150_000_000L);
        Player p = saveUnderAuction(5_000_000L);
        bidding.placeBid(p.getPlayerId(), team.getTeamId());

        bidding.undoBid(p.getPlayerId());
        assertNull(bidding.currentBidAmount(p.getPlayerId()));
        assertEquals(0, bidding.bidCount(p.getPlayerId()));

        var ex = assertThrows(AuctionException.class, () -> bidding.undoBid(p.getPlayerId()));
        assertEquals("NO_BIDS", ex.getCode());
        // The same team can open the bidding again at base price.
        assertEquals(5_000_000L, bidding.placeBid(p.getPlayerId(), team.getTeamId()).amount());
    }

    @Test
    void confirmSaleFlushesTheLiveTrailToTheDatabase() {
        Team t1 = saveTeam(150_000_000L);
        Team t2 = teams.save(TestFixtures.team("Rival", 150_000_000L, 8, Map.of()));
        Player p = saveUnderAuction(5_000_000L);
        bidding.placeBid(p.getPlayerId(), t1.getTeamId());
        bidding.placeBid(p.getPlayerId(), t2.getTeamId());
        bidding.placeBid(p.getPlayerId(), t1.getTeamId());
        assertEquals(0, bidEvents.countByPlayerId(p.getPlayerId()));

        sale.confirmSale(p.getPlayerId());

        var trail = bidEvents.findByPlayerIdOrderByBidNumberAsc(p.getPlayerId());
        assertEquals(3, trail.size());
        assertEquals(5_000_000L, trail.get(0).getAmount());
        assertEquals(7_000_000L, trail.get(2).getAmount()); // 5M, 6M, 7M
        assertEquals(3, trail.get(2).getBidNumber());
    }
}
