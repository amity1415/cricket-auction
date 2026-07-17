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

    /** Wipes a single player's persisted bid trail — used when a sale is reverted. */
    void deleteByPlayerId(UUID playerId);

    void deleteAll();
}
