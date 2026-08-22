package com.auctiontracker.config;

import com.auctiontracker.config.AuctionProperties.CategoryRule;
import com.auctiontracker.config.AuctionProperties.GroupTransition;
import com.auctiontracker.config.AuctionProperties.IncrementRule;
import com.auctiontracker.config.AuctionProperties.Retention;
import com.auctiontracker.config.AuctionProperties.TeamDefaults;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.tournament.Tournament;
import com.auctiontracker.tournament.TournamentContext;
import com.auctiontracker.tournament.TournamentRepository;
import com.auctiontracker.tournament.TournamentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.auctiontracker.core.PlayerCategory.A;
import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerCategory.C;
import static com.auctiontracker.core.PlayerCategory.D;

/**
 * Seeds the "KCPL Season 2" tournament — a STANDARD_POOL auction that exercises
 * the KCPL 2 format end to end: four pools A→B→C→D, the group-budget
 * carry-forward chain (₹60L → ₹50L → ₹8L → ₹2L, unspent rolls forward),
 * pre-auction Icon/Owner retentions kept OFF the pool budgets, and the
 * no-demotion-of-A / B→C / C→D / sticky-D unsold cascade.
 *
 * <p>Idempotent: it does nothing once a tournament of this name exists, so it
 * never touches or duplicates data on restart, and it leaves every other
 * tournament (the legacy KCPL and ABPL) completely untouched. Players are NOT
 * seeded — the organizer imports the real 40/60/45/25 pool via the setup page.
 *
 * <p>All numbers here are just the <em>initial</em> configuration — they live in
 * the tournament's rule book and can be edited afterwards from the auction editor.
 * Runs after {@link com.auctiontracker.tournament.TournamentBootstrap}.
 * See {@code docs/KCPL2-FORMAT-DESIGN.md} for the derivation of every value.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class KcplSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KcplSeeder.class);
    private static final String NAME = "KCPL Season 2";

    private final TournamentService tournaments;
    private final TournamentRepository tournamentRepo;
    private final CoreService core;

    public KcplSeeder(TournamentService tournaments, TournamentRepository tournamentRepo, CoreService core) {
        this.tournaments = tournaments;
        this.tournamentRepo = tournamentRepo;
        this.core = core;
    }

    @Override
    public void run(String... args) {
        boolean exists = tournamentRepo.findAllByOrderByCreatedAtAsc().stream()
                .anyMatch(t -> NAME.equalsIgnoreCase(t.getName()));
        if (exists) {
            return; // already seeded — leave it (and everything else) alone
        }
        try {
            Tournament t = tournaments.create(NAME, kcplRules());
            t.setAuctionRuleType("STANDARD_POOL");
            tournamentRepo.save(t);

            // Register the 10 teams within this tournament's context so they're
            // scoped to it and validated against its rule book.
            TournamentContext.set(t.getId());
            try {
                // Real KCPL 2 franchises — names match the Icon/Owner gradings in
                // the CricHeroes players sheet, so the import can pre-assign each
                // pick to its team by name.
                for (String team : List.of(
                        "Challengers", "Fighters", "Honey B", "Indians", "Knights",
                        "Lions", "Predators", "Thunders", "Titans", "Warriors")) {
                    core.registerTeam(team, team + " Owner", 15_000_000L, 20, Map.of());
                }
            } finally {
                TournamentContext.clear();
            }
            log.info("Seeded STANDARD_POOL tournament '{}' ({}) with 10 teams (carry-forward pools A–D)",
                    NAME, t.getId());
        } catch (Exception e) {
            TournamentContext.clear();
            log.warn("KCPL Season 2 seed skipped: {}", e.getMessage());
        }
    }

    /** The initial KCPL Season 2 rule book — a fully data-driven carry-forward config. */
    private AuctionProperties kcplRules() {
        // Base prices, in pool order (A ₹3L, B ₹1L, C ₹50K, D ₹20K).
        Map<PlayerCategory, Long> basePrices = new LinkedHashMap<>();
        basePrices.put(A, 300_000L);
        basePrices.put(B, 100_000L);
        basePrices.put(C, 50_000L);
        basePrices.put(D, 20_000L);

        // Per-team squad composition + pool budgets.               max, min, reserve/slot, budget
        Map<PlayerCategory, CategoryRule> quotas = new LinkedHashMap<>();
        quotas.put(A, new CategoryRule(4, 4, 300_000L, 6_000_000L)); // ₹60L, 4-player base reserve
        quotas.put(B, new CategoryRule(6, 3, null, 5_000_000L));     // ₹50L
        quotas.put(C, new CategoryRule(null, 3, null, 800_000L));    // ₹8L, no max
        quotas.put(D, new CategoryRule(null, 2, null, 200_000L));    // ₹2L, no max, sticky floor
        // Σ budgets = ₹120L = ₹150L purse − ₹30L pre-auction retentions.

        // Unsold cascade. A has NO demotion (self-transition keeps it in A and
        // AVAILABLE for re-auction); B→C, C→D re-price to the destination's base;
        // D is the sticky floor (self-transition), re-auctioned and, if still
        // unbought, awarded by lottery via the manual/floor bid. A null destination
        // price means "use the destination pool's own base price".
        Map<PlayerCategory, GroupTransition> transitions = new LinkedHashMap<>();
        transitions.put(A, new GroupTransition(A, null)); // no demotion — stays in A
        transitions.put(B, new GroupTransition(C, null)); // → Pool C at ₹50K
        transitions.put(C, new GroupTransition(D, null)); // → Pool D at ₹20K
        transitions.put(D, new GroupTransition(D, null)); // never past D — sticky floor

        return new AuctionProperties(
                20_000L,                                        // minViablePrice (= Pool D base)
                basePrices,
                List.of(new IncrementRule(50_000L, 5_000L),     // ₹20K–50K  → +₹5K
                        new IncrementRule(100_000L, 10_000L),   // ₹50K–1L   → +₹10K
                        new IncrementRule(300_000L, 20_000L)),  // ₹1L–3L    → +₹20K
                25_000L,                                        // ₹3L onwards → +₹25K
                quotas,
                // 2 Icon @ ₹12L + 1 Owner @ ₹6L = ₹30L. Per-group caps enforce the
                // KCPL split (2 Icons, 1 Owner) rather than the generic A/lower one.
                new Retention(3, 2, 1, 1_200_000L, 600_000L,
                        Map.of(PlayerCategory.ICON, 2, PlayerCategory.OWNER, 1)),
                new TeamDefaults(15_000_000L, 20),              // purse ₹1.5Cr, squad 20
                false,                                          // no ordinal demotion — use transitions
                false,                                          // seedDemoData
                transitions,
                null,                                           // flat retention fees, not a multiplier
                List.of(A, B, C, D),                            // group / carry-forward sequence
                true,                                           // budgetCarryForward ON
                false);                                         // retentions kept OFF the pool budgets
    }
}
