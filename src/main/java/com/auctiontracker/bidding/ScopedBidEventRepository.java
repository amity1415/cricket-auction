package com.auctiontracker.bidding;

import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The {@link BidEventRepository} the services see — stamps the active tournament
 * on saved events and scopes the bulk wipe to it. Player-id reads are already
 * tournament-unique.
 */
@Repository
public class ScopedBidEventRepository implements BidEventRepository {

    private final BidEventJpaRepository jpa;
    private final RuleBook ruleBook;

    public ScopedBidEventRepository(BidEventJpaRepository jpa, RuleBook ruleBook) {
        this.jpa = jpa;
        this.ruleBook = ruleBook;
    }

    @Override
    public BidEvent save(BidEvent event) {
        if (event.getTournamentId() == null) {
            event.setTournamentId(ruleBook.activeTournamentId());
        }
        return jpa.save(event);
    }

    @Override
    public List<BidEvent> findByPlayerIdOrderByBidNumberAsc(UUID playerId) {
        return jpa.findByPlayerIdOrderByBidNumberAsc(playerId);
    }

    @Override
    public long countByPlayerId(UUID playerId) {
        return jpa.countByPlayerId(playerId);
    }

    @Override
    public void deleteByPlayerId(UUID playerId) {
        jpa.deleteByPlayerId(playerId); // player id is tournament-unique — no scoping needed
    }

    @Override
    public void deleteAll() {
        UUID tid = ruleBook.activeTournamentId();
        if (tid == null) {
            jpa.deleteAllInBatch();
        } else {
            jpa.deleteByTournamentId(tid);
        }
    }
}
