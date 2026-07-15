package com.auctiontracker.config;

import com.auctiontracker.config.AuctionProperties.CategoryRule;
import com.auctiontracker.config.AuctionProperties.GroupTransition;
import com.auctiontracker.config.AuctionProperties.IncrementRule;
import com.auctiontracker.config.AuctionProperties.Retention;
import com.auctiontracker.config.AuctionProperties.TeamDefaults;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRole;
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

import static com.auctiontracker.core.PlayerCategory.ALL_ROUNDER;
import static com.auctiontracker.core.PlayerCategory.BOWLER;
import static com.auctiontracker.core.PlayerCategory.MARKEE_PLAYER;
import static com.auctiontracker.core.PlayerCategory.MIXED_UTILITY_BAG;
import static com.auctiontracker.core.PlayerCategory.WICKET_KEEPER;

/**
 * Seeds the demo "ABPL Season 2" tournament — a ROLE_BASED_CASCADE auction that
 * exercises the new configurable format end to end (5 role groups, per-group
 * min/max, the unsold cascade with rising base prices, and 3× base-price
 * retention). Idempotent: it does nothing once the tournament exists, so it never
 * touches or duplicates data on restart, and it leaves every other tournament
 * (KCPL and the A–E format) completely untouched.
 *
 * <p>All numbers here are just the <em>initial</em> configuration for this
 * tournament — they live in its rule book and can be edited afterwards. Runs
 * after {@link com.auctiontracker.tournament.TournamentBootstrap} so the default
 * tournament and schema migrations are already in place.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AbplSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AbplSeeder.class);
    private static final String NAME = "ABPL Season 2";

    private final TournamentService tournaments;
    private final TournamentRepository tournamentRepo;
    private final CoreService core;

    public AbplSeeder(TournamentService tournaments, TournamentRepository tournamentRepo, CoreService core) {
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
            Tournament t = tournaments.create(NAME, roleBasedCascadeRules());
            t.setAuctionRuleType("ROLE_BASED_CASCADE");
            tournamentRepo.save(t);

            // Register teams and players within this tournament's context so they
            // are scoped to it and validated against its rule book.
            TournamentContext.set(t.getId());
            try {
                for (String team : List.of("Royal Rebels", "Titan Warriors", "Metro Mavericks",
                        "Coastal Kings", "Emerald Eagles", "Phoenix Force")) {
                    core.registerTeam(team, team + " Owner", 1_500_000L, 8, Map.of());
                }
                seedPlayers();
            } finally {
                TournamentContext.clear();
            }
            log.info("Seeded ROLE_BASED_CASCADE tournament '{}' ({}) with 6 teams and 56 players",
                    NAME, t.getId());
        } catch (Exception e) {
            TournamentContext.clear();
            log.warn("ABPL Season 2 seed skipped: {}", e.getMessage());
        }
    }

    /** The initial ABPL rule book — a fully data-driven ROLE_BASED_CASCADE config. */
    private AuctionProperties roleBasedCascadeRules() {
        Map<PlayerCategory, Long> basePrices = new LinkedHashMap<>();
        basePrices.put(MIXED_UTILITY_BAG, 20_000L);
        basePrices.put(WICKET_KEEPER, 40_000L);
        basePrices.put(BOWLER, 40_000L);
        basePrices.put(ALL_ROUNDER, 100_000L);
        basePrices.put(MARKEE_PLAYER, 80_000L);

        Map<PlayerCategory, CategoryRule> quotas = new LinkedHashMap<>();
        //                                              max, min, reservePerSlot, budget
        quotas.put(MIXED_UTILITY_BAG, new CategoryRule(2, 1, null, null));
        quotas.put(WICKET_KEEPER,     new CategoryRule(2, 1, null, null));
        quotas.put(BOWLER,            new CategoryRule(2, 1, null, null));
        quotas.put(ALL_ROUNDER,       new CategoryRule(3, 2, null, null));
        quotas.put(MARKEE_PLAYER,     new CategoryRule(2, 1, null, null)); // Markee max: configurable

        // Unsold cascade. A null destination price means the transferred player
        // restarts at the destination pool's own base price (Mixed→Bowler ₹40k,
        // WK/Bowler→All Rounder ₹1L, All Rounder→Markee ₹80k) — and auto-adjusts if
        // a pool's base price is retuned.
        Map<PlayerCategory, GroupTransition> transitions = new LinkedHashMap<>();
        transitions.put(MIXED_UTILITY_BAG, new GroupTransition(BOWLER, null));
        transitions.put(WICKET_KEEPER,     new GroupTransition(ALL_ROUNDER, null));
        transitions.put(BOWLER,            new GroupTransition(ALL_ROUNDER, null));
        transitions.put(ALL_ROUNDER,       new GroupTransition(MARKEE_PLAYER, null));
        // MARKEE_PLAYER is terminal (no entry) → unsold there is finally UNSOLD.

        return new AuctionProperties(
                20_000L,                                        // minViablePrice
                basePrices,
                List.of(new IncrementRule(100_000L, 5_000L),
                        new IncrementRule(500_000L, 10_000L)),  // increment bands
                20_000L,                                        // defaultIncrement
                quotas,
                new Retention(3, 0, 3, 0L, 0L),                 // up to 3 retentions; cost via multiplier
                new TeamDefaults(1_500_000L, 8),                // purse ₹15L, squad 8
                false,                                          // no ordinal demotion — use transitions
                false,                                          // seedDemoData
                transitions,
                3);                                             // retention = 3 × base price
    }

    /** The 56 named players from the ABPL Season 2 sheet, with per-player base prices. */
    private void seedPlayers() {
        // Markee Player (base ₹80k)
        markee("Ratan Mandal");           markee("Abhishek Chatterjee");   markee("Subhojyoti Das");
        markee("Sayantan Chakraborty");   markee("Anjan Biswas");          markee("Kunal Mukherjee");
        markee("Annweshan Mukherjee");

        // All Rounder (base ₹1L; Pradipta Sen at ₹3L)
        allRounder("Mithun Das", 100_000);        allRounder("Anirban Ghosh (Suvo)", 100_000);
        allRounder("Subhasish Bhattacharjee", 100_000); allRounder("Prasenjit Saha", 100_000);
        allRounder("Arkojit Chowdhury", 100_000);  allRounder("Nilojit Das", 100_000);
        allRounder("Rajasman Lahiri", 100_000);    allRounder("Pradipta Sen", 300_000);
        allRounder("Anandarup DasGupta", 100_000); allRounder("Soumyojit Das", 100_000);
        allRounder("Rajesh Laskar", 100_000);      allRounder("Indrajit Sarkar", 100_000);
        allRounder("Sushmit Das", 100_000);        allRounder("Chiranjit Biswas", 100_000);
        allRounder("Aneek Sardar", 100_000);       allRounder("Sumonto Bose (Rohit)", 100_000);
        allRounder("Rahul Nath (Deep)", 100_000);

        // Bowler (base ₹60k; several at ₹1.8L)
        bowler("Dipanjan Rudra (Saibal)", 60_000);   bowler("Debdip Mukherjee (Rahul)", 180_000);
        bowler("Kishor Das", 180_000);               bowler("Anirban Das (Rana)", 180_000);
        bowler("Dhrubajyoti Darkar (Guddu)", 180_000); bowler("Subhojit Mondal", 60_000);
        bowler("Sandeep Roy (Sandy)", 60_000);       bowler("Dipak Malakar", 60_000);
        bowler("Sayan Chakraborty", 60_000);         bowler("Kapil Basu", 60_000);

        // Wicket Keeper (base ₹40k; two at ₹1.2L)
        keeper("Abhishek Mukherjee (Rohan)", 120_000); keeper("Sandip Roy (Bappa)", 120_000);
        keeper("Dipanjan Nath", 40_000);               keeper("Ranadip Banerjee (Gorki)", 40_000);
        keeper("Biswadeep Mukherjee", 40_000);         keeper("Sujit Mondal", 40_000);
        keeper("Rajdeep Nath (David)", 40_000);        keeper("Bhombol", 40_000);

        // Mixed Bag Utility (base ₹20k)
        mixed("Subal Sardar");            mixed("Sandipan Mitra");          mixed("Subhradeep Roy");
        mixed("Priyanshu Chatterjee (Papu)"); mixed("Baidik Mukherjee (Prince)"); mixed("Raju Mandal");
        mixed("Annay De (Ribhu)");        mixed("Alok Sarkar");             mixed("Joydeep Dasgupta");
        mixed("Surya Sanyal");            mixed("Shrawan Ghoshal");         mixed("Rahul Sardar");
        mixed("Somdeep Dey (Ujjan)");     mixed("Biswanath Dhara");
    }

    // The auction group is what drives this format; the cricket role is display-only
    // and inferred from the group (a player's master role isn't given in the sheet).
    private void markee(String name)                 { core.registerPlayer(name, PlayerRole.BATSMAN, MARKEE_PLAYER, 80_000L, null); }
    private void allRounder(String name, long base)  { core.registerPlayer(name, PlayerRole.ALL_ROUNDER, ALL_ROUNDER, base, null); }
    private void bowler(String name, long base)      { core.registerPlayer(name, PlayerRole.BOWLER, BOWLER, base, null); }
    private void keeper(String name, long base)      { core.registerPlayer(name, PlayerRole.WICKETKEEPER, WICKET_KEEPER, base, null); }
    private void mixed(String name)                  { core.registerPlayer(name, PlayerRole.ALL_ROUNDER, MIXED_UTILITY_BAG, 20_000L, null); }
}
