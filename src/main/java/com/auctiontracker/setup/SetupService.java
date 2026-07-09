package com.auctiontracker.setup;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerRowParser;
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
import java.util.List;
import java.util.Locale;

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
            return core.replaceAllPlayers(parsed);
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
            return rows;
        } catch (IOException | RuntimeException e) {
            throw AuctionException.badRequest("INVALID_IMPORT",
                    "Could not read the .xlsx file: " + e.getMessage());
        }
    }
}
