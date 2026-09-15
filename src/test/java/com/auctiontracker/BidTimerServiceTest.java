package com.auctiontracker;

import com.auctiontracker.bidding.BidTimerService;
import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.bidding.InMemoryBidEventRepository;
import com.auctiontracker.bidding.InMemoryLiveBidStore;
import com.auctiontracker.bidding.IncrementRuleEngine;
import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.InMemoryPlayerRepository;
import com.auctiontracker.core.InMemoryTeamRepository;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import com.auctiontracker.sale.InMemorySaleRepository;
import com.auctiontracker.sale.SaleService;
import com.auctiontracker.tournament.RuleBook;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The online per-lot auto-close: an idle lot sells to the current leader (or is
 * marked unsold when nobody bid) once its window passes, and every fresh bid
 * resets the countdown. The timer authority is {@link BidTimerService#sweepActive}
 * reading the store's expiring deadline; tests drive the sweep directly rather than
 * waiting on the scheduler.
 */
class BidTimerServiceTest {

    private InMemoryPlayerRepository players;
    private InMemoryTeamRepository teams;
    private BiddingService bidding;
    private BidTimerService bidTimer;

    private void wire(Integer timerSeconds) {
        players = new InMemoryPlayerRepository();
        teams = new InMemoryTeamRepository();
        AuctionProperties props = TestFixtures.onlineProps(timerSeconds);
        RuleBook ruleBook = RuleBook.fixed(props);
        var feasibility = new FeasibilityService(players, ruleBook);
        var lock = new AuctionLock();
        var store = new InMemoryLiveBidStore(lock);
        bidding = new BiddingService(players, teams, new InMemoryBidEventRepository(),
                new IncrementRuleEngine(ruleBook), feasibility, lock, ruleBook, store);
        SaleService sale = new SaleService(players, teams, new InMemorySaleRepository(),
                feasibility, lock, ruleBook, bidding);
        bidTimer = new BidTimerService(store, sale, ruleBook);
    }

    private Team saveTeam(String name) {
        return teams.save(TestFixtures.team(name, 150_000_000L, 8, Map.of()));
    }

    private Player saveUnderAuction(long basePrice) {
        Player p = players.save(TestFixtures.player("Arjun", BATSMAN, B, basePrice));
        bidding.markUnderAuction(p.getPlayerId());
        return p;
    }

    private PlayerStatus status(UUID playerId) {
        return players.findById(playerId).orElseThrow().getStatus();
    }

    /** Sweeps every 50ms until the lot closes or the budget runs out. */
    private PlayerStatus awaitClose(UUID playerId, long maxMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline) {
            bidTimer.sweepActive();
            if (status(playerId) != PlayerStatus.UNDER_AUCTION) {
                return status(playerId);
            }
            Thread.sleep(50);
        }
        return status(playerId);
    }

    @Test
    void idleLotAutoSellsToTheLeader() throws InterruptedException {
        wire(1);
        Team team = saveTeam("Chennai");
        Player p = saveUnderAuction(5_000_000L);
        bidding.placeBid(p.getPlayerId(), team.getTeamId(), 6_000_000L);

        PlayerStatus after = awaitClose(p.getPlayerId(), 4_000);

        assertEquals(PlayerStatus.SOLD, after);
        Player sold = players.findById(p.getPlayerId()).orElseThrow();
        assertEquals(team.getTeamId(), sold.getSoldToTeamId());
        assertEquals(6_000_000L, sold.getSoldPrice());
        assertEquals(150_000_000L - 6_000_000L,
                teams.findById(team.getTeamId()).orElseThrow().getRemainingPurse());
    }

    @Test
    void idleLotWithNoBidsAutoUnsolds() throws InterruptedException {
        wire(1);
        saveTeam("Chennai");
        Player p = saveUnderAuction(5_000_000L);

        PlayerStatus after = awaitClose(p.getPlayerId(), 4_000);

        // No leading bid → the lot closes as unsold (re-pooled or terminal per rules),
        // never sold. The key guarantee: it does not stay stuck UNDER_AUCTION.
        assertNotEquals(PlayerStatus.UNDER_AUCTION, after);
        assertNotEquals(PlayerStatus.SOLD, after);
    }

    @Test
    void aFreshBidResetsTheCountdown() throws InterruptedException {
        wire(2);
        Team a = saveTeam("A");
        Team b = saveTeam("B");
        Player p = saveUnderAuction(5_000_000L);

        bidding.placeBid(p.getPlayerId(), a.getTeamId(), 6_000_000L);   // deadline ~ +2s
        Thread.sleep(1_200);
        bidTimer.sweepActive();                                          // ~1.2s: not due yet
        bidding.placeBid(p.getPlayerId(), b.getTeamId(), 7_000_000L);   // reset → deadline ~ +2s
        Thread.sleep(1_200);                                            // ~2.4s: original deadline passed, reset one hasn't
        bidTimer.sweepActive();

        assertEquals(PlayerStatus.UNDER_AUCTION, status(p.getPlayerId()),
                "the countdown should have been reset by the second bid");

        // Let the reset window expire — the lot now sells to the last bidder.
        PlayerStatus after = awaitClose(p.getPlayerId(), 3_000);
        assertEquals(PlayerStatus.SOLD, after);
        assertEquals(b.getTeamId(),
                players.findById(p.getPlayerId()).orElseThrow().getSoldToTeamId());
    }
}
