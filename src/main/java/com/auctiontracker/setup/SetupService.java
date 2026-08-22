package com.auctiontracker.setup;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerRowParser;
import com.auctiontracker.core.Team;
import com.auctiontracker.sale.SaleService;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pre-auction setup orchestration: replace-import of the whole player pool
 * from a CSV or Excel (.xlsx) file. Replacing wipes all auction progress —
 * bid history, sale audit, team squads and purses — then loads the new pool,
 * all in one transaction. Depends only on the other modules' facades.
 */
@Service
public class SetupService {

    private final CoreService core;
    private final BiddingService bidding;
    private final SaleService sale;
    private final PlayerRowParser parser;
    private final AuctionLock lock;

    public SetupService(CoreService core, BiddingService bidding, SaleService sale,
                        PlayerRowParser parser, AuctionLock lock) {
        this.core = core;
        this.bidding = bidding;
        this.sale = sale;
        this.parser = parser;
        this.lock = lock;
    }

    @Transactional
    public List<Player> replaceImport(String filename, byte[] content) {
        List<Player> parsed = isXlsx(filename)
                ? parser.parseRows(readXlsxRows(content), "row")
                : parser.parseCsv(new String(content, StandardCharsets.UTF_8));
        synchronized (lock) {
            bidding.clearLiveSession();
            bidding.deleteAllBidEvents();
            sale.deleteAllSales();
            List<Player> saved = core.replaceAllPlayers(parsed);
            assignPreAuctionPicks(saved);
            // Re-read so the response reflects the post-assignment state (Icon/Owner
            // picks now RETAINED), in import order, rather than the parsed objects.
            return core.listPlayers(null, null, null);
        }
    }

    /**
     * Pre-assign the imported Icon/Owner picks to their team. Each such row carries
     * the team name parsed from its grading ("Icon - Titans"); we match it to a
     * team by name (case-insensitive) and retain the player there at its base price
     * (₹12L Icon / ₹6L Owner). A pick whose team name matches no team is left
     * AVAILABLE in its group, to be assigned by hand on the retention screen.
     */
    private void assignPreAuctionPicks(List<Player> players) {
        Map<String, UUID> teamByName = new HashMap<>();
        for (Team t : core.listTeams()) {
            teamByName.put(t.getName().trim().toLowerCase(Locale.ROOT), t.getTeamId());
        }
        for (Player p : players) {
            String teamName = p.getPreAssignedTeamName();
            if (teamName == null || teamName.isBlank()) continue;
            UUID teamId = teamByName.get(teamName.trim().toLowerCase(Locale.ROOT));
            if (teamId == null) continue;   // no matching team → leave the pick unassigned
            sale.assignPreAuction(teamId, p.getPlayerId(), p.getBasePrice());
        }
    }

    private static boolean isXlsx(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    /** First sheet only; cells rendered the way Excel displays them. */
    private List<PlayerRowParser.Row> readXlsxRows(byte[] content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            Sheet sheet = workbook.getSheetAt(0);
            List<PlayerRowParser.Row> rows = new ArrayList<>();
            for (Row row : sheet) {
                int lastCell = row.getLastCellNum();
                if (lastCell < 0) continue;
                String[] fields = new String[lastCell];
                boolean hasContent = false;
                for (int c = 0; c < lastCell; c++) {
                    fields[c] = formatter.formatCellValue(row.getCell(c)).trim();
                    if (!fields[c].isEmpty()) hasContent = true;
                }
                if (hasContent) rows.add(new PlayerRowParser.Row(row.getRowNum() + 1, fields));
            }
            mergeSectionHeader(rows);
            return rows;
        } catch (IOException | RuntimeException e) {
            throw AuctionException.badRequest("INVALID_IMPORT",
                    "Could not read the .xlsx file: " + e.getMessage());
        }
    }

    /**
     * Collapse a two-row "section banner + column" header into one header row.
     * The KCPL CricHeroes export puts a merged {@code BATTING STATS / BOWLING
     * STATS} banner above a column row that repeats {@code Innings}, {@code Runs},
     * {@code Avg} and {@code SR} in both sections. Forward-filling the banner and
     * prefixing each column with its section ({@code Batting Innings}, {@code
     * Bowling Innings}, …) makes those columns unambiguous for the parser. A file
     * with an ordinary single header row (no banner) is left untouched.
     */
    private static void mergeSectionHeader(List<PlayerRowParser.Row> rows) {
        if (rows.size() < 2) return;
        String[] banner = rows.get(0).fields();
        String[] header = rows.get(1).fields();
        if (!hasSectionBanner(banner) || !isHeaderRow(header)) return;
        String[] combined = new String[header.length];
        String section = "";
        for (int c = 0; c < header.length; c++) {
            if (c < banner.length && banner[c] != null && !banner[c].isBlank()) {
                String b = banner[c].toLowerCase(Locale.ROOT);
                section = b.contains("batting") ? "Batting " : b.contains("bowling") ? "Bowling " : "";
            }
            String h = header[c] == null ? "" : header[c];
            combined[c] = section.isEmpty() || h.isBlank() ? h : section + h;
        }
        rows.set(1, new PlayerRowParser.Row(rows.get(1).number(), combined));
        rows.remove(0);
    }

    private static boolean hasSectionBanner(String[] cells) {
        for (String c : cells) {
            if (c == null) continue;
            String s = c.toLowerCase(Locale.ROOT);
            if (s.contains("batting") || s.contains("bowling")) return true;
        }
        return false;
    }

    private static boolean isHeaderRow(String[] cells) {
        for (String c : cells) {
            if (c != null && c.trim().equalsIgnoreCase("name")) return true;
        }
        return false;
    }
}
