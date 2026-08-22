package com.auctiontracker;

import com.auctiontracker.tournament.RuleBook;

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

import static com.auctiontracker.core.PlayerCategory.A;
import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerCategory.ICON;
import static com.auctiontracker.core.PlayerCategory.OWNER;
import static com.auctiontracker.core.PlayerRole.ALL_ROUNDER;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static com.auctiontracker.core.PlayerRole.WICKETKEEPER;
import com.auctiontracker.core.PlayerStatus;
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
        var parser = new PlayerRowParser(RuleBook.fixed(props));
        var feasibility = new FeasibilityService(players, RuleBook.fixed(props));
        var lock = new AuctionLock();
        core = new CoreService(players, teams, RuleBook.fixed(props), parser);
        bidding = new BiddingService(players, teams, bidEvents,
                new IncrementRuleEngine(RuleBook.fixed(props)), feasibility, lock, RuleBook.fixed(props));
        sale = new SaleService(players, teams, sales, feasibility, lock, RuleBook.fixed(props), bidding);
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

    /**
     * The KCPL CricHeroes export: a two-row header (a BATTING/BOWLING banner over
     * a column row that repeats Innings/Runs/Avg/SR), a Grading column with
     * Icon/Owner pre-auction picks, and roles spelled with spaces. All of it must
     * import — sections disambiguated, grading collapsed to the ICON pool, and the
     * free-text Highest score / Best bowling captured verbatim.
     */
    @Test
    void xlsxImportReadsKcplTwoRowHeaderSheet() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("KCPL 2 Players + Stats");

            // Column layout: 0 No, 1 Name, 2 Grading, 3 Role, 4 Speciality, 5 Link,
            // 6-12 batting (Matches,Innings,Not out,Runs,Highest Runs,Avg,SR),
            // 13-17 bowling (Matches,Innings,Wickets,Economy,Best Bowling).
            Row banner = sheet.createRow(0);
            banner.createCell(0).setCellValue("PLAYER DETAILS (from KCPL 2 list)");
            banner.createCell(6).setCellValue("BATTING STATS (CricHeroes career)");
            banner.createCell(13).setCellValue("BOWLING STATS (CricHeroes career)");

            Row header = sheet.createRow(1);
            String[] cols = {"Player's No.", "Name", "Grading", "Player's Role",
                    "Player's Speciality", "CricHeroes Profile Link",
                    "Matches", "Innings", "Not out", "Runs", "Highest Runs", "Avg", "SR",
                    "Matches", "Innings", "Wickets", "Economy", "Best Bowling"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);

            Row icon = sheet.createRow(2);
            String[] iconRow = {"1", "Dharam Paramanik", "Icon - Titans", "All Rounder",
                    "Middle Order Batsman", "https://chshare.link/player/x",
                    "106", "100", "8", "1806", "97", "19.63", "179.52",
                    "106", "49", "46", "9.46", "3/16"};
            for (int i = 0; i < iconRow.length; i++) icon.createCell(i).setCellValue(iconRow[i]);

            Row keeper = sheet.createRow(3);
            String[] keeperRow = {"2", "Aakash Banka", "A", "Wicket Keeper",
                    "Top Order Batsman", "https://chshare.link/player/y",
                    "847", "774", "169", "21347", "155*", "35.28", "193.8",
                    "847", "587", "633", "9.75", "5/13"};
            for (int i = 0; i < keeperRow.length; i++) keeper.createCell(i).setCellValue(keeperRow[i]);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            var imported = setup.replaceImport("KCPL2.xlsx", out.toByteArray());
            assertEquals(2, imported.size());

            Player dharam = players.findAll().stream()
                    .filter(p -> p.getName().equals("Dharam Paramanik")).findFirst().orElseThrow();
            assertEquals(ICON, dharam.getCategory());
            assertEquals(ALL_ROUNDER, dharam.getRole());
            assertEquals(1_200_000L, dharam.getBasePrice()); // Icon default — no base in the sheet
            assertEquals(100, dharam.getStats().battingInnings());
            assertEquals(1806, dharam.getStats().runs());
            assertEquals("97", dharam.getStats().highestScore());
            assertEquals(49, dharam.getStats().bowlingInnings());
            assertEquals(46, dharam.getStats().wickets());
            assertEquals(9.46, dharam.getStats().economyRate());
            assertEquals("3/16", dharam.getStats().bestBowling());

            Player aakash = players.findAll().stream()
                    .filter(p -> p.getName().equals("Aakash Banka")).findFirst().orElseThrow();
            assertEquals(A, aakash.getCategory());
            assertEquals(WICKETKEEPER, aakash.getRole());
            assertEquals("155*", aakash.getStats().highestScore()); // not-out marker preserved
            assertEquals("5/13", aakash.getStats().bestBowling());
        }
    }

    /**
     * Icon/Owner picks land in their own ICON/OWNER groups and are pre-assigned to
     * the team named in the grading — RETAINED at the base price (₹12L Icon / ₹6L
     * Owner), purse deducted. A pick whose team name matches no team is left
     * AVAILABLE in its group for manual assignment.
     */
    @Test
    void xlsxImportAssignsIconAndOwnerPicksToTheirTeams() throws Exception {
        // Teams the gradings reference (matched by name); purse reset to starting on import.
        // Fixture scale (see other retention tests): purse 150M, squad 8.
        Team titans = teams.save(TestFixtures.team("Titans", 150_000_000L, 8, Map.of()));
        Team warriors = teams.save(TestFixtures.team("Warriors", 150_000_000L, 8, Map.of()));

        byte[] xlsx = kcplSheet(new String[][]{
            {"1", "Icon Guy", "Icon - Titans", "All Rounder"},
            {"2", "Owner Guy", "Owner - Warriors", "Batsman"},
            {"3", "Orphan Icon", "Icon - Nomads", "Bowler"},   // no such team
        });
        var imported = setup.replaceImport("KCPL2.xlsx", xlsx);
        assertEquals(3, imported.size());

        Player icon = byName("Icon Guy");
        assertEquals(ICON, icon.getCategory());
        assertEquals(PlayerStatus.RETAINED, icon.getStatus());
        assertEquals(titans.getTeamId(), icon.getSoldToTeamId());
        assertEquals(1_200_000L, icon.getSoldPrice());            // ₹12L Icon base
        assertEquals(148_800_000L, titans.getRemainingPurse());   // 150M purse − 12L

        Player owner = byName("Owner Guy");
        assertEquals(OWNER, owner.getCategory());
        assertEquals(PlayerStatus.RETAINED, owner.getStatus());
        assertEquals(warriors.getTeamId(), owner.getSoldToTeamId());
        assertEquals(600_000L, owner.getSoldPrice());             // ₹6L Owner base
        assertEquals(149_400_000L, warriors.getRemainingPurse()); // 150M purse − 6L

        Player orphan = byName("Orphan Icon");                  // team not found → unassigned
        assertEquals(ICON, orphan.getCategory());
        assertEquals(PlayerStatus.AVAILABLE, orphan.getStatus());
        assertNull(orphan.getSoldToTeamId());
    }

    private Player byName(String name) {
        return players.findAll().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    /** Build a minimal KCPL-shaped .xlsx: banner + column header, then the given rows. */
    private static byte[] kcplSheet(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("KCPL 2 Players + Stats");
            Row banner = sheet.createRow(0);
            banner.createCell(0).setCellValue("PLAYER DETAILS");
            banner.createCell(4).setCellValue("BATTING STATS (CricHeroes career)");
            Row header = sheet.createRow(1);
            String[] cols = {"Player's No.", "Name", "Grading", "Player's Role", "Runs"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 2);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }
}
