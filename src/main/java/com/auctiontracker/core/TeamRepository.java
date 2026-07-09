package com.auctiontracker.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for teams. See {@link PlayerRepository} for the swap-to-JPA plan. */
public interface TeamRepository {

    Team save(Team team);

    Optional<Team> findById(UUID teamId);

    List<Team> findAll();

    long count();

    void deleteById(UUID teamId);
}
