package com.auctiontracker.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for players. The v1 implementation is an in-memory map;
 * this interface is shaped so a Spring Data JPA repository can replace it
 * without touching the services.
 */
public interface PlayerRepository {

    Player save(Player player);

    Optional<Player> findById(UUID playerId);

    List<Player> findAll();

    Optional<Player> findFirstByStatus(PlayerStatus status);

    List<Player> findBySoldToTeamId(UUID teamId);

    long count();

    void deleteAll();

    void deleteById(UUID playerId);
}
