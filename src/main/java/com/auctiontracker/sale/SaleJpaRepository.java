package com.auctiontracker.sale;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw Spring Data access for the sale/unsold audit log. {@link ScopedSaleRepository}
 * scopes it to the active tournament and implements the {@link SaleRepository} port.
 */
public interface SaleJpaRepository extends JpaRepository<Sale, UUID> {

    List<Sale> findByTournamentIdOrderByRecordedAtAsc(UUID tournamentId);

    /** Most recent SOLD/UNSOLD for one tournament — uses the (tournament_id, recorded_at) index. */
    Optional<Sale> findFirstByTournamentIdAndTypeInOrderByRecordedAtDesc(UUID tournamentId, Collection<Sale.Type> types);

    /** Unscoped variant (no active tournament, e.g. tests). */
    Optional<Sale> findFirstByTypeInOrderByRecordedAtDesc(Collection<Sale.Type> types);

    /** Bulk DELETE for one tournament — no entity loading. */
    @Modifying
    @Query("delete from Sale s where s.tournamentId = :tid")
    void deleteByTournamentId(@Param("tid") UUID tournamentId);

    /** Bulk DELETE of a single player's audit rows (a player id is tournament-unique). */
    @Modifying
    @Query("delete from Sale s where s.playerId = :pid")
    void deleteByPlayerId(@Param("pid") UUID playerId);
}
