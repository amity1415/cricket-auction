package com.auctiontracker.bidding;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port for bid events; append-only. Method names follow Spring Data
 * derivation rules so the JPA implementation is generated from them.
 */
public interface BidEventRepository {

    BidEvent save(BidEvent event);

    List<BidEvent> findByPlayerIdOrderByBidNumberAsc(UUID playerId);

    long countByPlayerId(UUID playerId);

    void deleteAll();
}
