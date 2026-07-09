package com.auctiontracker;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.bidding.InMemoryBidEventRepository;
import com.auctiontracker.bidding.IncrementRuleEngine;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.InMemoryPlayerRepository;
import com.auctiontracker.core.InMemoryTeamRepository;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerRowParser;
import com.auctiontracker.core.Team;
import com.auctiontracker.sale.InMemorySaleRepository;
import com.auctiontracker.sale.SaleService;
import com.auctiontracker.setup.SetupService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Setup-page replace import: wipes auction state, loads the new pool, parses stats, reads .xlsx. */
class SetupServiceTest {

    private InMemoryPlayerRepository players;
    private InMemoryTeamRepository teams;
    private InMemoryBidEventRepository bidEvents;
    private InMemorySaleRepository sales;
    private CoreService core;
    private BiddingService bidding;
    private SaleService sale;
    private SetupService setup;

    @BeforeEach
    void setUp() {
        players = new InMemoryPlayerRepository();
        teams = new InMemoryTeamRepository();
        bidEvents = new InMemoryBidEventRepository();
        sales = new InMemorySaleRepository();
        var props = TestFixtures.props();
        var parser = new PlayerRowParser(props);
        var feasibility = new FeasibilityService(players, props);
        var lock = new AuctionLock();
        core = new CoreService(players, teams, props, parser);
        bidding = new BiddingService(players, teams, bidEvents,
                new IncrementRuleEngine(props), feasibility, lock);
        sale = new SaleService(players, teams, sales, feasibility, lock, props, bidding);
        setup = new SetupService(core, bidding, sale, parser, lock);
    }

    @Test
    void replaceImportWipesAuctionStateAndLoadsNewPool() {
        // A completed sale: purse deducted, squad filled, audit + bid history written.
        Team team = teams.save(TestFixtures.team("Chennai Chargers", 150_000_000L, 8, Map.of()));
        Player old = players.save(TestFixtures.player("Old Player", BATSMAN, B, 5_000_000L));
        bidding.markUnderAuction(old.getPlayerId());
        bidding.placeBid(old.getPlayerId(), team.getTeamId());
        sale.confirmSale(old.getPlayerId());
        assertEquals(145_000_000L, team.getRemainingPurse());

        String csv = """
                name,role,category,basePrice,matches,runs,battingAvg,strikeRate,wickets,economy
                Fresh Batter,BATSMAN,B,,120,3400,38.5,135.2,,
                Quick Bowler,BOWLER,C,3000000,80,95,8.5,88.0,102,7.2
                """;
        var imported = setup.replaceImport("players.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, imported.size());
        assertEquals(2, players.count());
        assertEquals(0, bidEvents.countByPlayerId(old.getPlayerId()));
        assertEquals(0, sales.findAllByOrderByRecordedAtAsc().size());
        assertEquals(150_000_000L, team.getRemainingPurse());
        assertTrue(team.getSquadPlayerIds().isEmpty());

        Player batter = players.findAll().get(0); // sorted by name → Fresh Batter
        assertEquals("Fresh Batter", batter.getName());
        assertEquals(120, batter.getStats().matches());
        assertEquals(38.5, batter.getStats().battingAverage());
        assertNull(batter.getStats().wickets());
        assertEquals(5_000_000L, batter.getBasePrice()); // blank → category B default
    }

    @Test
    void replaceImportRejectsBadRowsWithoutWipingAnything() {
        players.save(TestFixtures.player("Keeper", BATSMAN, B, 5_000_000L));

        var ex = assertThrows(AuctionException.class, () ->
                setup.replaceImport("players.csv",
                        "Good,BATSMAN,B\nBad,NOT_A_ROLE,B".getBytes(StandardCharsets.UTF_8)));

        assertEquals("INVALID_IMPORT", ex.getCode());
        assertEquals(1, players.count()); // parse failed before any wipe
    }

    @Test
    void xlsxImportParsesRowsAndStats() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("players");
            String[] header = {"name", "role", "category", "basePrice",
                    "matches", "runs", "battingAvg", "strikeRate", "wickets", "economy"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) headerRow.createCell(i).setCellValue(header[i]);
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("Excel Star");
            r1.createCell(1).setCellValue("ALL_ROUNDER");
            r1.createCell(2).setCellValue("A");
            r1.createCell(3).setCellValue(12_000_000);
            r1.createCell(4).setCellValue(95);
            r1.createCell(5).setCellValue(1800);
            r1.createCell(6).setCellValue(27.7);
            r1.createCell(7).setCellValue(132.4);
            r1.createCell(8).setCellValue(66);
            r1.createCell(9).setCellValue(8.3);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            var imported = setup.replaceImport("players.xlsx", out.toByteArray());

            assertEquals(1, imported.size());
            Player p = players.findAll().get(0);
            assertEquals("Excel Star", p.getName());
            assertEquals(12_000_000L, p.getBasePrice());
            assertEquals(95, p.getStats().matches());
            assertEquals(27.7, p.getStats().battingAverage());
            assertEquals(66, p.getStats().wickets());
            assertEquals(8.3, p.getStats().economyRate());
        }
    }
}
