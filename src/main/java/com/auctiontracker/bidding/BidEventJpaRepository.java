package com.auctiontracker.bidding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data implementation of the {@link BidEventRepository} port. */
public interface BidEventJpaRepository extends BidEventRepository, JpaRepository<BidEvent, UUID> {

    /** Single bulk DELETE — no entity loading. */
    @Override
    default void deleteAll() {
        deleteAllInBatch();
    }
}
