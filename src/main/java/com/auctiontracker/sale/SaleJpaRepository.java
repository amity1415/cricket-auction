package com.auctiontracker.sale;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data implementation of the {@link SaleRepository} port. */
public interface SaleJpaRepository extends SaleRepository, JpaRepository<Sale, UUID> {

    /** Single bulk DELETE — no entity loading. */
    @Override
    default void deleteAll() {
        deleteAllInBatch();
    }
}
