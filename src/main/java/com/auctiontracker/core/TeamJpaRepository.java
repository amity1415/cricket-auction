package com.auctiontracker.core;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Raw Spring Data access for teams. {@link ScopedTeamRepository} scopes it to
 * the active tournament and implements the {@link TeamRepository} port.
 */
public interface TeamJpaRepository extends JpaRepository<Team, UUID> {

    List<Team> findByTournamentId(UUID tournamentId);

    long countByTournamentId(UUID tournamentId);
}
