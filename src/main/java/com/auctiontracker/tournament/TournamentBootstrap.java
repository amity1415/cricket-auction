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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
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
    private final DataSource dataSource;

    @PersistenceContext
    private EntityManager em;

    public TournamentBootstrap(TournamentRepository tournaments, RuleBook ruleBook,
                               DataSource dataSource) {
        this.tournaments = tournaments;
        this.ruleBook = ruleBook;
        this.dataSource = dataSource;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // Runs on its OWN JDBC connection (isolated from this transaction) so a
        // failure/no-op on H2 can't poison the bootstrap below.
        migrateLobRulesJson();
        relaxUserAccountTeamId();

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

    /**
     * One-time repair for rows written by the earlier {@code @Lob} mapping: on
     * Postgres that stored {@code rules_json} as a large object, leaving an OID
     * (a bare integer) in the text column — unreadable outside a transaction, so
     * every list/read 500s. Convert any such OID back to inline JSON text. The
     * {@code ~} regex and {@code lo_get} are Postgres-only; on H2 the query throws
     * and we skip (H2 rows are already inline text). Idempotent — only bare-integer
     * values match, and after conversion they no longer do.
     */
    private void migrateLobRulesJson() {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase().contains("postgre")) {
                return; // large-object storage is a Postgres-only artifact
            }
            // lo_get needs a real transaction (large objects can't be read in
            // auto-commit mode) — hence the explicit commit on our own connection.
            boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                int n = st.executeUpdate(
                        "UPDATE tournament SET rules_json = convert_from(lo_get(rules_json::oid), 'UTF8') "
                                + "WHERE rules_json ~ '^[0-9]+$'");
                c.commit();
                if (n > 0) {
                    log.info("Migrated {} tournament(s) from large-object rules_json to inline text", n);
                }
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAutoCommit);
            }
        } catch (Exception e) {
            log.warn("rules_json large-object migration skipped: {}", e.getMessage());
        }
    }

    /**
     * Tournament-admin accounts own no team, so {@code user_account.team_id} must
     * be nullable. {@code ddl-auto=update} never relaxes an existing NOT NULL, so
     * do it once here (Postgres only; {@code DROP NOT NULL} is a no-op if already
     * nullable). On H2 the fresh schema is already nullable, so we skip.
     */
    private void relaxUserAccountTeamId() {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase().contains("postgre")) {
                return;
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE user_account ALTER COLUMN team_id DROP NOT NULL");
            }
        } catch (Exception e) {
            log.warn("user_account.team_id NOT NULL relax skipped: {}", e.getMessage());
        }
    }

    /** Bulk-assigns the tournament to every row of the entity that has none yet. */
    private int backfill(String entity, UUID tournamentId) {
        return em.createQuery(
                        "update " + entity + " e set e.tournamentId = :tid where e.tournamentId is null")
                .setParameter("tid", tournamentId)
                .executeUpdate();
    }
}
