package com.auctiontracker.tournament;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Ensures a default tournament exists and that all pre-existing data belongs to
 * it. On the very first boot after this feature ships, the existing KCPL data
 * (players, teams, sales, bid events, owners) has {@code tournament_id = null};
 * this creates the "KCPL" tournament — seeded from the application.yml rule book
 * so it behaves exactly as before — makes it active, and backfills every null
 * {@code tournament_id} to it.
 *
 * Runs before the demo {@code DataSeeder} so any seeded rows are stamped with the
 * active tournament. Idempotent: once a tournament exists it does nothing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TournamentBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TournamentBootstrap.class);

    private final TournamentRepository tournaments;
    private final RuleBook ruleBook;

    @PersistenceContext
    private EntityManager em;

    public TournamentBootstrap(TournamentRepository tournaments, RuleBook ruleBook) {
        this.tournaments = tournaments;
        this.ruleBook = ruleBook;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (tournaments.count() > 0) {
            // A tournament already exists — make sure RuleBook knows the active one.
            tournaments.findFirstByActiveTrue()
                    .ifPresent(t -> ruleBook.activeChanged(t.getId()));
            return;
        }

        Tournament kcpl = Tournament.create("KCPL", "kcpl",
                ruleBook.serialize(ruleBook.defaults()));
        kcpl.setActive(true);
        tournaments.save(kcpl);
        ruleBook.activeChanged(kcpl.getId());

        UUID id = kcpl.getId();
        int players = backfill("Player", id);
        int teams = backfill("Team", id);
        int sales = backfill("Sale", id);
        int bids = backfill("BidEvent", id);
        int owners = backfill("UserAccount", id);
        log.info("Bootstrapped default tournament KCPL ({}) and backfilled tournament_id — "
                + "players={}, teams={}, sales={}, bidEvents={}, owners={}",
                id, players, teams, sales, bids, owners);
    }

    /** Bulk-assigns the tournament to every row of the entity that has none yet. */
    private int backfill(String entity, UUID tournamentId) {
        return em.createQuery(
                        "update " + entity + " e set e.tournamentId = :tid where e.tournamentId is null")
                .setParameter("tid", tournamentId)
                .executeUpdate();
    }
}
