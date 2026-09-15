package com.auctiontracker.bidding;

import com.auctiontracker.sale.SaleService;
import com.auctiontracker.tournament.RuleBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Drives the online per-lot auto-close. Every second each app instance sweeps the
 * active auction and asks the {@link LiveBidStore} whether the on-block lot's
 * countdown has expired. {@link LiveBidStore#claimExpiredClose} hands the lot to
 * exactly one caller — clearing the deadline as it does — so across a whole cluster
 * the lot is closed once: sold to the leader, or unsold if nobody bid.
 *
 * <p>Because the authority is the store's atomic claim (not a per-JVM timer), this
 * is correct whether the store is the single-instance in-memory one or the shared
 * Redis one. Offline auctions and online auctions with no timer set no deadline, so
 * the sweep finds nothing and does nothing.
 */
@Service
public class BidTimerService {

    private static final Logger log = LoggerFactory.getLogger(BidTimerService.class);

    private final LiveBidStore store;
    private final SaleService sale;
    private final RuleBook ruleBook;

    public BidTimerService(LiveBidStore store, SaleService sale, RuleBook ruleBook) {
        this.store = store;
        this.sale = sale;
        this.ruleBook = ruleBook;
    }

    /**
     * One sweep of the active auction. Runs on a schedule in the app; also called
     * directly by tests. Safe to run on every instance — only the one that wins the
     * atomic claim actually closes the lot.
     */
    @Scheduled(fixedDelayString = "${auction.live.sweep-ms:1000}")
    public void sweepActive() {
        UUID tid = ruleBook.activeTournamentId();
        try {
            store.claimExpiredClose(tid).ifPresent(this::close);
        } catch (RuntimeException e) {
            // A transient store hiccup (e.g. a Redis blip) must not kill the scheduler.
            log.debug("Bid-timer sweep skipped: {}", e.getMessage());
        }
    }

    private void close(UUID playerId) {
        try {
            sale.autoClose(playerId);
        } catch (RuntimeException e) {
            log.warn("Auto-close of player {} failed; lot stays open: {}", playerId, e.getMessage());
        }
    }
}
