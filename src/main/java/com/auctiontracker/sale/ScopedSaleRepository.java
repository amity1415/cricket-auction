package com.auctiontracker.sale;

import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The {@link SaleRepository} the services see — scopes the audit log to the active
 * tournament and stamps it on newly-saved rows.
 */
@Repository
public class ScopedSaleRepository implements SaleRepository {

    private final SaleJpaRepository jpa;
    private final RuleBook ruleBook;

    public ScopedSaleRepository(SaleJpaRepository jpa, RuleBook ruleBook) {
        this.jpa = jpa;
        this.ruleBook = ruleBook;
    }

    @Override
    public Sale save(Sale sale) {
        if (sale.getTournamentId() == null) {
            sale.setTournamentId(ruleBook.activeTournamentId());
        }
        return jpa.save(sale);
    }

    @Override
    public List<Sale> findAllByOrderByRecordedAtAsc() {
        UUID tid = ruleBook.activeTournamentId();
        return tid == null
                ? jpa.findAll(org.springframework.data.domain.Sort.by("recordedAt").ascending())
                : jpa.findByTournamentIdOrderByRecordedAtAsc(tid);
    }

    @Override
    public void deleteAll() {
        UUID tid = ruleBook.activeTournamentId();
        if (tid == null) {
            jpa.deleteAllInBatch();
        } else {
            jpa.deleteByTournamentId(tid);
        }
    }
}
