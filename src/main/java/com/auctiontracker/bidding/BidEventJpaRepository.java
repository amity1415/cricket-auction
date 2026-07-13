package com.auctiontracker.bidding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Raw Spring Data access for bid events. {@link ScopedBidEventRepository} scopes
 * the bulk wipe to the active tournament and implements the {@link BidEventRepository}
 * port. Reads are by player id (already tournament-unique) so need no scoping.
 */
public interface BidEventJpaRepository extends JpaRepository<BidEvent, UUID> {

    java.util.List<BidEvent> findByPlayerIdOrderByBidNumberAsc(UUID playerId);

    long countByPlayerId(UUID playerId);

    /** Bulk DELETE for one tournament — no entity loading. */
    @Modifying
    @Query("delete from BidEvent b where b.tournamentId = :tid")
    void deleteByTournamentId(@Param("tid") UUID tournamentId);
}
