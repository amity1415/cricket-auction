package com.auctiontracker.sale;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Raw Spring Data access for the sale/unsold audit log. {@link ScopedSaleRepository}
 * scopes it to the active tournament and implements the {@link SaleRepository} port.
 */
public interface SaleJpaRepository extends JpaRepository<Sale, UUID> {

    List<Sale> findByTournamentIdOrderByRecordedAtAsc(UUID tournamentId);

    /** Bulk DELETE for one tournament — no entity loading. */
    @Modifying
    @Query("delete from Sale s where s.tournamentId = :tid")
    void deleteByTournamentId(@Param("tid") UUID tournamentId);
}
