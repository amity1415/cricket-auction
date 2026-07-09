package com.auctiontracker.config;

import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRole;
import com.auctiontracker.core.PlayerStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.auctiontracker.core.PlayerCategory.A;
import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerCategory.C;
import static com.auctiontracker.core.PlayerCategory.D;
import static com.auctiontracker.core.PlayerCategory.E;
import static com.auctiontracker.core.PlayerRole.ALL_ROUNDER;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static com.auctiontracker.core.PlayerRole.BOWLER;
import static com.auctiontracker.core.PlayerRole.WICKETKEEPER;

/**
 * Seeds demo teams and players at startup so the auction can be driven
 * immediately. Skips itself if the database already has data (a persistent
 * DB keeps its rows across restarts). Disable with auction.seed-demo-data=false.
 */
@Component
@ConditionalOnProperty(prefix = "auction", name = "seed-demo-data", havingValue = "true")
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final CoreService core;
    private final AuctionProperties props;

    public DataSeeder(CoreService core, AuctionProperties props) {
        this.core = core;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        if (!core.listTeams().isEmpty()) {
            log.info("Database already has teams — skipping demo-data seeding");
            return;
        }
        team("Chennai Chargers", "R. Menon");
        team("Delhi Dynamos", "V. Kapoor");
        team("Kolkata Kings", "S. Bose");
        team("Mumbai Mavericks", "A. Shah");

        // Group A (top tier)                               matches  runs   avg    SR     wkts  econ
        player("Arjun Sharma", BATSMAN, A, false,           142, 4820, 41.2, 138.5, null, null);
        player("Marcus Bell", BATSMAN, A, true,             165, 5602, 43.8, 142.1, null, null);
        player("Dale Rivers", BOWLER, A, true,              130,  312, 12.4, 110.0,  168,  7.4);
        player("Rohan Iyer", ALL_ROUNDER, A, false,         118, 2140, 28.9, 131.0,   96,  8.1);
        // Group B
        player("Kane Foster", WICKETKEEPER, B, true,        121, 3230, 34.0, 128.7, null, null);
        player("Vikram Rao", BOWLER, B, false,               98,  145,  9.1,  92.0,  121,  7.9);
        player("Siddharth Jain", BATSMAN, B, false,         104, 2980, 36.3, 125.4, null, null);
        player("Liam Carter", ALL_ROUNDER, B, true,          89, 1675, 26.2, 134.9,   74,  8.4);
        player("Aditya Kulkarni", WICKETKEEPER, B, false,    76, 1890, 31.5, 122.0, null, null);
        // Group C
        player("Nikhil Verma", BOWLER, C, false,             67,   88,  8.0,  85.0,   78,  8.2);
        player("Dev Patel", BATSMAN, C, false,               58, 1450, 29.0, 119.8, null, null);
        player("Owen Blake", BOWLER, C, true,                72,   60,  6.7,  79.0,   84,  7.7);
        player("Karan Mehta", ALL_ROUNDER, C, false,         63,  940, 22.4, 126.3,   41,  8.6);
        player("Ishaan Reddy", WICKETKEEPER, C, false,       49, 1020, 26.8, 117.5, null, null);
        // Group D
        player("Sam Turner", BATSMAN, D, true,               34,  720, 24.0, 121.2, null, null);
        player("Rahul Nair", BOWLER, D, false,               28,   35,  7.0,  74.0,   31,  8.0);
        player("Pranav Joshi", BATSMAN, D, false,            22,  410, 20.5, 113.6, null, null);
        // Group E (lowest — unsold returns to the pool, never terminal)
        player("Harsh Gupta", ALL_ROUNDER, E, false,         26,  305, 17.9, 118.0,   19,  8.8);
        player("Tejas Kadam", BOWLER, E, false,              19,   22,  5.5,  68.0,   24,  7.6);
        player("Manoj Pillai", WICKETKEEPER, E, false,       17,  265, 18.9, 109.1, null, null);

        log.info("Seeded demo data: 4 teams (purse {} each), 20 players across groups A–E",
                props.teamDefaults().startingPurse());
    }

    private void team(String name, String owner) {
        AuctionProperties.TeamDefaults d = props.teamDefaults();
        // No role minimums and no overseas quota — those are display-only details.
        core.registerTeam(name, owner, d.startingPurse(), d.maxSquadSize(), Map.of(), 0);
    }

    private void player(String name, PlayerRole role, PlayerCategory category, boolean overseas,
                        Integer matches, Integer runs, Double avg, Double strikeRate,
                        Integer wickets, Double economy) {
        core.registerPlayer(name, role, category, null, overseas,
                new PlayerStats(matches, runs, avg, strikeRate, wickets, economy));
    }
}
