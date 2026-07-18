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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        relaxUserAccountOptionalColumns();
        dropStaleUserAccountRoleCheck();
        dropStaleEnumCheck("player", "category"); // role-based cascade added new group values

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
     * Columns every {@code user_account} row always has — these keep their NOT NULL.
     * Everything else (team_id, tournament_id, and any column left over from an
     * older schema) must be nullable: a TOURNAMENT_ADMIN owns no team, so it
     * inserts NULLs there.
     */
    private static final Set<String> USER_ACCOUNT_REQUIRED_COLUMNS =
            Set.of("id", "username", "password_hash", "display_name", "role", "created_at");

    /**
     * Relaxes any stale NOT NULL constraint on the optional {@code user_account}
     * columns. {@code ddl-auto=update} adds columns but never drops an existing
     * NOT NULL, so a constraint from an earlier schema (most importantly on
     * {@code team_id}, which is NULL for tournament admins) makes every such
     * insert fail with a generic 500 — the "unexpected error" when creating an
     * auction admin. Data-driven off {@code information_schema} so it also heals
     * orphaned columns from older naming, and idempotent (DROP NOT NULL on an
     * already-nullable column is a no-op). Runs on its own connection.
     */
    private void relaxUserAccountOptionalColumns() {
        try (Connection c = dataSource.getConnection()) {
            List<String> toRelax = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE lower(table_name) = 'user_account' AND is_nullable = 'NO'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString(1).toLowerCase();
                    if (!USER_ACCOUNT_REQUIRED_COLUMNS.contains(col)) {
                        toRelax.add(col);
                    }
                }
            }
            for (String col : toRelax) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE user_account ALTER COLUMN " + col + " DROP NOT NULL");
                    log.info("Relaxed stale NOT NULL on user_account.{}", col);
                } catch (Exception e) {
                    log.warn("Could not relax NOT NULL on user_account.{}: {}", col, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("user_account NOT NULL relax skipped: {}", e.getMessage());
        }
    }

    /**
     * Drops any CHECK constraint on the {@code user_account.role} column. For an
     * {@code @Enumerated(STRING)} column Hibernate emits {@code CHECK (role in (...))}
     * listing only the enum values that existed when the column was first created,
     * and {@code ddl-auto=update} never updates it. Once TOURNAMENT_ADMIN was added
     * to the enum, inserting one violates the stale constraint and the request 500s
     * — the "unexpected error" when creating an auction admin. The enum is enforced
     * in code, so the DB check is redundant; drop it. Portable across H2/Postgres
     * via information_schema, and idempotent (nothing to drop once gone).
     */
    private void dropStaleUserAccountRoleCheck() {
        dropStaleEnumCheck("user_account", "role");
    }

    /**
     * Drops any CHECK constraint on {@code table.column} that references the enum
     * column (skipping synthetic NOT NULL checks). Hibernate emits
     * {@code CHECK (col in (...))} for {@code @Enumerated(STRING)} columns listing
     * only the values present when the column was created; {@code ddl-auto=update}
     * never updates it, so adding a value to the enum breaks inserts on a
     * long-lived DB. Applies to {@code user_account.role} and, since the
     * role-based cascade groups were added, {@code player.category}. The enum is
     * enforced in code, so the DB check is redundant. Portable across H2/Postgres,
     * idempotent.
     */
    private void dropStaleEnumCheck(String table, String column) {
        try (Connection c = dataSource.getConnection()) {
            List<String> toDrop = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tc.constraint_name, cc.check_clause "
                            + "FROM information_schema.table_constraints tc "
                            + "JOIN information_schema.check_constraints cc "
                            + "  ON tc.constraint_name = cc.constraint_name "
                            + " AND tc.constraint_schema = cc.constraint_schema "
                            + "WHERE tc.constraint_type = 'CHECK' "
                            + "  AND lower(tc.table_name) = ?")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        String clause = String.valueOf(rs.getString(2)).toLowerCase();
                        // The enum value check references the column; skip NOT NULL checks.
                        if (clause.contains(column) && !clause.contains("not null")) {
                            toDrop.add(name);
                        }
                    }
                }
            }
            for (String name : toDrop) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE " + table + " DROP CONSTRAINT " + name);
                    log.info("Dropped stale {} CHECK constraint {} on {}", column, name, table);
                } catch (Exception e) {
                    log.warn("Could not drop CHECK constraint {} on {}: {}", name, table, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("{} {} CHECK cleanup skipped: {}", table, column, e.getMessage());
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
