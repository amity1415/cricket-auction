package com.auctiontracker;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.bidding.InMemoryBidEventRepository;
import com.auctiontracker.bidding.InMemoryLiveBidStore;
import com.auctiontracker.bidding.IncrementRuleEngine;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.InMemoryPlayerRepository;
import com.auctiontracker.core.InMemoryTeamRepository;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.Team;
import com.auctiontracker.tournament.RuleBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The concurrency contract behind owner-driven live bidding: many franchise owners
 * hitting the server at once must resolve to exactly one winner, with the highest
 * bid leading and an exact tie going to whichever request the lock admits first.
 * All of this rides on the in-memory session guarded by the shared {@link AuctionLock}
 * — no per-bid DB write — so these tests exercise {@link BiddingService#placeBid}
 * directly, the same path the {@code /api/live/bid} endpoint calls.
 */
class LiveBiddingConcurrencyTest {

    private InMemoryPlayerRepository players;
    private InMemoryTeamRepository teams;
    private BiddingService bidding;

    @BeforeEach
    void setUp() {
        players = new InMemoryPlayerRepository();
        teams = new InMemoryTeamRepository();
        var props = TestFixtures.props();
        var feasibility = new FeasibilityService(players, RuleBook.fixed(props));
        var lock = new AuctionLock();
        bidding = new BiddingService(players, teams, new InMemoryBidEventRepository(),
                new IncrementRuleEngine(RuleBook.fixed(props)), feasibility, lock,
                RuleBook.fixed(props), new InMemoryLiveBidStore(lock));
    }

    private Team saveTeam(String name) {
        return teams.save(TestFixtures.team(name, 150_000_000L, 8, Map.of()));
    }

    private Player saveUnderAuction(long basePrice) {
        Player p = players.save(TestFixtures.player("Arjun", BATSMAN, B, basePrice));
        bidding.markUnderAuction(p.getPlayerId());
        return p;
    }

    /** Two owners commit the SAME amount in sequence: the first holds it, the second is rejected. */
    @Test
    void secondBidAtTheSameAmountIsRejected() {
        Team first = saveTeam("Chennai");
        Team second = saveTeam("Mumbai");
        Player p = saveUnderAuction(5_000_000L);

        var winning = bidding.placeBid(p.getPlayerId(), first.getTeamId(), 6_000_000L);
        assertEquals(first.getTeamId(), winning.leadingTeam().getTeamId());

        var ex = assertThrows(AuctionException.class,
                () -> bidding.placeBid(p.getPlayerId(), second.getTeamId(), 6_000_000L));
        assertEquals("BID_TOO_LOW", ex.getCode());
        // The first owner still holds the lot at the tied amount.
        assertEquals(first.getTeamId(), bidding.currentLeadingTeamId(p.getPlayerId()));
        assertEquals(6_000_000L, bidding.currentBidAmount(p.getPlayerId()));
    }

    /** Whatever order two owners arrive in, the higher amount ends up leading. */
    @Test
    void higherBidLeadsRegardlessOfArrivalOrder() {
        Team high = saveTeam("High");
        Team low = saveTeam("Low");
        Player p = saveUnderAuction(5_000_000L);

        // Low arrives first, then High outbids.
        bidding.placeBid(p.getPlayerId(), low.getTeamId(), 5_500_000L);
        bidding.placeBid(p.getPlayerId(), high.getTeamId(), 7_000_000L);
        assertEquals(high.getTeamId(), bidding.currentLeadingTeamId(p.getPlayerId()));

        // Fresh lot, reversed order: High first, then Low's lower bid bounces.
        Player q = saveUnderAuction(5_000_000L);
        bidding.placeBid(q.getPlayerId(), high.getTeamId(), 7_000_000L);
        var ex = assertThrows(AuctionException.class,
                () -> bidding.placeBid(q.getPlayerId(), low.getTeamId(), 5_500_000L));
        assertEquals("BID_TOO_LOW", ex.getCode());
        assertEquals(high.getTeamId(), bidding.currentLeadingTeamId(q.getPlayerId()));
    }

    /**
     * The real race: eight owners fire the identical amount at the same instant.
     * Exactly one wins the lot; the other seven are rejected BID_TOO_LOW — never a
     * lost update, never two holders of the same price.
     */
    @Test
    void concurrentIdenticalBidsProduceExactlyOneWinner() throws InterruptedException {
        int owners = 8;
        List<Team> teamList = new java.util.ArrayList<>();
        for (int i = 0; i < owners; i++) {
            teamList.add(saveTeam("Team " + i));
        }
        Player p = saveUnderAuction(5_000_000L);
        long amount = 6_000_000L;

        var pool = Executors.newFixedThreadPool(owners);
        var ready = new CountDownLatch(owners);
        var go = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var rejections = new AtomicInteger();
        var winners = new CopyOnWriteArrayList<UUID>();

        for (Team team : teamList) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    bidding.placeBid(p.getPlayerId(), team.getTeamId(), amount);
                    successes.incrementAndGet();
                    winners.add(team.getTeamId());
                } catch (AuctionException e) {
                    if ("BID_TOO_LOW".equals(e.getCode())) {
                        rejections.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not start");
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "bids did not finish");

        assertEquals(1, successes.get(), "exactly one owner may hold the tied amount");
        assertEquals(owners - 1, rejections.get(), "every other owner is rejected BID_TOO_LOW");
        assertEquals(1, winners.size());
        // The sole winner is the leader on record — no lost update.
        assertEquals(winners.get(0), bidding.currentLeadingTeamId(p.getPlayerId()));
        assertEquals(amount, bidding.currentBidAmount(p.getPlayerId()));
    }
}
