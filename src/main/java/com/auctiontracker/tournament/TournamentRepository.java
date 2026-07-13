package com.auctiontracker.tournament;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Persistence for tournaments. */
public interface TournamentRepository extends JpaRepository<Tournament, UUID> {

    Optional<Tournament> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Optional<Tournament> findFirstByActiveTrue();

    java.util.List<Tournament> findAllByOrderByCreatedAtAsc();
}
