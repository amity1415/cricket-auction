package com.auctiontracker.sale;

import java.util.List;

/**
 * Persistence port for the sale/unsold audit log; append-only. Method names
 * follow Spring Data derivation rules.
 */
public interface SaleRepository {

    Sale save(Sale sale);

    List<Sale> findAllByOrderByRecordedAtAsc();

    /** Wipes every audit row for one player — used when a sale is reverted. */
    void deleteByPlayerId(java.util.UUID playerId);

    void deleteAll();
}
