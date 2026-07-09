package com.auctiontracker.sale;

import java.util.List;

/**
 * Persistence port for the sale/unsold audit log; append-only. Method names
 * follow Spring Data derivation rules.
 */
public interface SaleRepository {

    Sale save(Sale sale);

    List<Sale> findAllByOrderByRecordedAtAsc();

    void deleteAll();
}
