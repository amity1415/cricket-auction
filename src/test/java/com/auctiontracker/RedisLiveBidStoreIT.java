package com.auctiontracker;

import com.auctiontracker.bidding.LiveBidStore;
import com.auctiontracker.bidding.RedisLiveBidStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link RedisLiveBidStore} against a real (embedded) Redis, so the Lua
 * compare-and-set actually runs — the same guarantees the in-memory store is unit
 * tested for, but proving the shared-Redis path a horizontally-scaled deployment
 * relies on. This is the multi-instance contract: whatever order two callers arrive
 * in, exactly one holds a given amount and the highest bid leads.
 */
class RedisLiveBidStoreIT {

    private static RedisServer server;
    private static int port;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    private final UUID tid = UUID.randomUUID();
    private LiveBidStore store;

    @BeforeAll
    static void startRedis() {
        // Point the test at an already-running Redis with EXTERNAL_REDIS_PORT
        // (used locally where the bundled binary can't start); otherwise spin up
        // the embedded one. Either way, real Redis executes the Lua.
        String external = System.getenv("EXTERNAL_REDIS_PORT");
        if (external != null && !external.isBlank()) {
            port = Integer.parseInt(external.trim());
        } else {
            port = 16399;
            try {
                server = new RedisServer(port);
                server.start();
            } catch (Exception e) {
                // No usable Redis binary on this host (e.g. a macOS box without the
                // OpenSSL the bundled binary links against). Skip rather than fail —
                // this test runs wherever a real Redis can start (CI, Linux, prod-like).
                server = null;
                Assumptions.abort("Embedded Redis could not start here: " + e.getMessage());
            }
        }
        factory = new LettuceConnectionFactory("localhost", port);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (factory != null) {
            factory.destroy();
        }
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void freshStore() {
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
        store = new RedisLiveBidStore(template);
    }

    private UUID player() {
        return UUID.randomUUID();
    }

    @Test
    void openTrackAndCloseRoundTrip() {
        UUID p = player();
        store.open(tid, p, 30);
        assertEquals(p, store.currentPlayer(tid));
        assertTrue(store.isFor(tid, p));
        assertNull(store.last(tid, p));
        assertEquals(0, store.count(tid, p));
        assertNotNull(store.deadline(tid, p), "a 30s timer should arm a deadline");

        store.close(tid, p);
        assertNull(store.currentPlayer(tid));
        assertNull(store.deadline(tid, p));
    }

    @Test
    void firstBidAtAnAmountWinsAndTheTieLoserIsRejected() {
        UUID p = player();
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        store.open(tid, p, null);

        LiveBidStore.PushOutcome first = store.tryPush(tid, p, teamA, 600_000, 500_000, null);
        assertEquals(LiveBidStore.PushStatus.OK, first.status());
        assertEquals(1, first.count());

        // Same amount from another team — the leader already holds it.
        LiveBidStore.PushOutcome tie = store.tryPush(tid, p, teamB, 600_000, 500_000, null);
        assertEquals(LiveBidStore.PushStatus.TOO_LOW, tie.status());
        assertEquals(600_000L, tie.leadingAmount());

        // A team cannot outbid itself.
        LiveBidStore.PushOutcome self = store.tryPush(tid, p, teamA, 700_000, 500_000, null);
        assertEquals(LiveBidStore.PushStatus.SELF_OUTBID, self.status());

        // A genuinely higher bid from B takes the lead.
        LiveBidStore.PushOutcome higher = store.tryPush(tid, p, teamB, 700_000, 500_000, null);
        assertEquals(LiveBidStore.PushStatus.OK, higher.status());
        assertEquals(teamB, store.last(tid, p).teamId());
        assertEquals(700_000L, store.last(tid, p).amount());
        assertEquals(2, store.count(tid, p));
    }

    @Test
    void bidBelowBaseIsRejected() {
        UUID p = player();
        store.open(tid, p, null);
        LiveBidStore.PushOutcome low = store.tryPush(tid, p, UUID.randomUUID(), 400_000, 500_000, null);
        assertEquals(LiveBidStore.PushStatus.BELOW_BASE, low.status());
        assertEquals(0, store.count(tid, p));
    }

    @Test
    void bidOnAStaleLotIsNotLive() {
        UUID p = player();
        store.open(tid, p, null);
        LiveBidStore.PushOutcome out = store.tryPush(tid, UUID.randomUUID(), UUID.randomUUID(),
                600_000, 500_000, null);
        assertEquals(LiveBidStore.PushStatus.NOT_LIVE, out.status());
    }

    @Test
    void concurrentIdenticalBidsProduceExactlyOneWinner() throws InterruptedException {
        UUID p = player();
        store.open(tid, p, null);
        int owners = 8;
        List<UUID> teams = new java.util.ArrayList<>();
        for (int i = 0; i < owners; i++) {
            teams.add(UUID.randomUUID());
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(owners);
        var go = new java.util.concurrent.CountDownLatch(1);
        var oks = new java.util.concurrent.atomic.AtomicInteger();
        var lows = new java.util.concurrent.atomic.AtomicInteger();
        for (UUID team : teams) {
            pool.submit(() -> {
                try {
                    go.await();
                    LiveBidStore.PushOutcome o = store.tryPush(tid, p, team, 600_000, 500_000, null);
                    if (o.status() == LiveBidStore.PushStatus.OK) oks.incrementAndGet();
                    else if (o.status() == LiveBidStore.PushStatus.TOO_LOW) lows.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, oks.get(), "exactly one owner may hold the tied amount");
        assertEquals(owners - 1, lows.get(), "every other owner is rejected TOO_LOW");
        assertEquals(1, store.count(tid, p), "only one bid is on the trail");
    }

    @Test
    void expiredLotIsClaimedByExactlyOneCaller() {
        UUID p = player();
        store.open(tid, p, 30);
        // No expiry yet.
        assertTrue(store.claimExpiredClose(tid).isEmpty());

        // Force the deadline into the past, then claim.
        template.opsForValue().set("lb:" + tid + ":d", String.valueOf(System.currentTimeMillis() - 1));
        assertEquals(p, store.claimExpiredClose(tid).orElseThrow());
        // A second claim finds nothing — the deadline was cleared atomically.
        assertTrue(store.claimExpiredClose(tid).isEmpty());
    }

    @Test
    void undoRemovesTheLastBid() {
        UUID p = player();
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        store.open(tid, p, null);
        store.tryPush(tid, p, teamA, 600_000, 500_000, null);
        store.tryPush(tid, p, teamB, 700_000, 500_000, null);

        LiveBidStore.Step removed = store.popLast(tid, p, null);
        assertEquals(teamB, removed.teamId());
        assertEquals(600_000L, store.last(tid, p).amount());
        assertEquals(1, store.count(tid, p));
    }
}
