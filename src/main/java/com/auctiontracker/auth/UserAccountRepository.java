package com.auctiontracker.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for franchise-owner accounts. */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByTeamId(UUID teamId);

    /** Owners registered in one tournament (for the admin owner list). */
    List<UserAccount> findByTournamentId(UUID tournamentId);

    /** All accounts of a role, e.g. every tournament admin (for the users page). */
    List<UserAccount> findByRole(Role role);

    /** Removes all owner accounts of a tournament (used when it is deleted). */
    void deleteByTournamentId(UUID tournamentId);
}
