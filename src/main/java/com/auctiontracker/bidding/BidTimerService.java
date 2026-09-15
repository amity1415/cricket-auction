package com.auctiontracker.bidding;

import com.auctiontracker.sale.SaleService;
import com.auctiontracker.tournament.Tournament;
import com.auctiontracker.tournament.TournamentContext;
import com.auctiontracker.tournament.TournamentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives the online per-lot auto-close. Every second each app instance sweeps
 * EVERY tournament and asks the {@link LiveBidStore} whether that tournament's
 * on-block lot has expired. {@link LiveBidStore#claimExpiredClose} hands the lot
 * to exactly one caller — clearing the deadline as it does — so across a cluster
 * the lot closes once: sold to the leader, or unsold if nobody bid. The close
 * runs bound to the lot's own tournament, so several auctions can run at once and
 * each closes in its own context.
 *
 * <p>Because the authority is the store's atomic claim (not a per-JVM timer), this
 * is correct whether the store is the single-instance in-memory one or the shared
 * Redis one. Offline auctions and online auctions with no timer set no deadline, so
 * the sweep finds nothing and does nothing.
 */
@Service
public class BidTimerService {

    private static final Logger log = LoggerFactory.getLogger(BidTimerService.class);

    /** How long the tournament-id list is cached between DB refreshes. */
    private static final long ID_CACHE_MILLIS = 20_000;

    private final LiveBidStore store;
    private final SaleService sale;
    private final TournamentRepository tournaments;   // null in unit tests (single, id-less auction)

    private volatile List<UUID> cachedIds = List.of();
    private volatile long cacheUntil = 0;

    public BidTimerService(LiveBidStore store, SaleService sale, TournamentRepository tournaments) {
        this.store = store;
        this.sale = sale;
        this.tournaments = tournaments;
    }

    /**
     * One sweep across every auction. Runs on a schedule in the app; also called
     * directly by tests. Safe to run on every instance — only the one that wins the
     * atomic claim actually closes a lot.
     */
    @Scheduled(fixedDelayString = "${auction.live.sweep-ms:1000}")
    public void sweepActive() {
        // The id-less (default) session covers bootstrap/tests; the rest are the
        // real tournaments, any of which may have a live lot.
        claimAndClose(null);
        for (UUID tournamentId : tournamentIds()) {
            claimAndClose(tournamentId);
        }
    }

    private void claimAndClose(UUID tournamentId) {
        try {
            Optional<UUID> playerId = store.claimExpiredClose(tournamentId);
            playerId.ifPresent(pid -> close(tournamentId, pid));
        } catch (RuntimeException e) {
            // A transient store hiccup (e.g. a Redis blip) must not kill the scheduler.
            log.debug("Bid-timer sweep skipped for tournament {}: {}", tournamentId, e.getMessage());
        }
    }

    private void close(UUID tournamentId, UUID playerId) {
        if (tournamentId != null) {
            TournamentContext.set(tournamentId);   // close in the lot's own auction
        }
        try {
            sale.autoClose(playerId);
        } catch (RuntimeException e) {
            log.warn("Auto-close of player {} (tournament {}) failed; lot stays open: {}",
                    playerId, tournamentId, e.getMessage());
        } finally {
            if (tournamentId != null) {
                TournamentContext.clear();
            }
        }
    }

    private List<UUID> tournamentIds() {
        if (tournaments == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (now >= cacheUntil) {
            try {
                List<UUID> ids = new ArrayList<>();
                for (Tournament t : tournaments.findAll()) {
                    ids.add(t.getId());
                }
                cachedIds = ids;
            } catch (RuntimeException e) {
                log.debug("Could not refresh tournament list for the bid-timer sweep: {}", e.getMessage());
            }
            cacheUntil = now + ID_CACHE_MILLIS;
        }
        return cachedIds;
    }
}
