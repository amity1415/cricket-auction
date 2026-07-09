package com.auctiontracker.core;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data implementation of the {@link PlayerRepository} port. All port
 * methods are either provided by JpaRepository or derived from their names.
 */
public interface PlayerJpaRepository extends PlayerRepository, JpaRepository<Player, UUID> {

    /** Single bulk DELETE — no entity loading (fast, and old rows never touch the enum mappers). */
    @Override
    default void deleteAll() {
        deleteAllInBatch();
    }
}
