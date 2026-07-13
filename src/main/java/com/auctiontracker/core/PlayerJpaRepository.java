package com.auctiontracker.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw Spring Data access for players. Reads/writes are scoped to the active
 * tournament by {@link ScopedPlayerRepository}, which implements the
 * {@link PlayerRepository} port the services depend on.
 */
public interface PlayerJpaRepository extends JpaRepository<Player, UUID> {

    List<Player> findByTournamentId(UUID tournamentId);

    Optional<Player> findFirstByStatusAndTournamentId(PlayerStatus status, UUID tournamentId);

    List<Player> findBySoldToTeamId(UUID teamId);

    long countByTournamentId(UUID tournamentId);

    /** Bulk DELETE for one tournament — no entity loading (old rows never touch the enum mappers). */
    @Modifying
    @Query("delete from Player p where p.tournamentId = :tid")
    void deleteByTournamentId(@Param("tid") UUID tournamentId);
}
