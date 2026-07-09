package com.auctiontracker.core;

import com.auctiontracker.config.AuctionProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses tabular player rows into {@link Player}s — shared by the CSV bulk
 * import and the .xlsx setup import. Column layout, header row optional:
 * <pre>name,role,category,overseas[,basePrice][,matches,runs,battingAvg,strikeRate,wickets,economy]</pre>
 * basePrice blank = the category's configured default; stats blank = not shown.
 * All-or-nothing: any bad row rejects the whole import with every error listed.
 */
@Component
public class PlayerRowParser {

    /** One raw row plus its 1-based position in the source file (for error messages). */
    public record Row(int number, String[] fields) {}

    private final AuctionProperties props;

    public PlayerRowParser(AuctionProperties props) {
        this.props = props;
    }

    public List<Player> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            throw AuctionException.badRequest("EMPTY_IMPORT", "CSV body is empty");
        }
        List<Row> rows = new ArrayList<>();
        String[] lines = csv.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            rows.add(new Row(i + 1, line.split(",", -1)));
        }
        return parseRows(rows, "line");
    }

    /** @param rowWord "line" (CSV) or "row" (Excel) — used in error messages. */
    public List<Player> parseRows(List<Row> rows, String rowWord) {
        List<Player> parsed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean first = true;
        for (Row row : rows) {
            boolean header = first && row.fields().length > 0
                    && row.fields()[0].trim().equalsIgnoreCase("name");
            first = false;
            if (header) continue;
            try {
                parsed.add(parseFields(row.fields()));
            } catch (Exception e) {
                errors.add(rowWord + " " + row.number() + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw AuctionException.badRequest("INVALID_IMPORT", String.join("; ", errors));
        }
        if (parsed.isEmpty()) {
            throw AuctionException.badRequest("EMPTY_IMPORT", "No player rows found");
        }
        return parsed;
    }

    private Player parseFields(String[] parts) {
        if (parts.length < 4) {
            throw new IllegalArgumentException(
                    "expected name,role,category,overseas[,basePrice,matches,runs,battingAvg,strikeRate,wickets,economy]");
        }
        String name = parts[0].trim();
        if (name.isEmpty()) throw new IllegalArgumentException("name is blank");
        PlayerRole role = PlayerRole.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
        PlayerCategory category = PlayerCategory.valueOf(parts[2].trim().toUpperCase(Locale.ROOT));
        String overseasRaw = parts[3].trim().toLowerCase(Locale.ROOT);
        boolean overseas = overseasRaw.equals("true") || overseasRaw.equals("yes") || overseasRaw.equals("1");
        long basePrice = hasValue(parts, 4) ? Long.parseLong(parts[4].trim()) : props.basePriceFor(category);
        if (basePrice <= 0) throw new IllegalArgumentException("base price must be positive");

        Player player = Player.register(name, role, category, basePrice, overseas);
        PlayerStats stats = new PlayerStats(
                intAt(parts, 5), intAt(parts, 6), doubleAt(parts, 7),
                doubleAt(parts, 8), intAt(parts, 9), doubleAt(parts, 10));
        player.setStats(stats.allNull() ? null : stats);
        return player;
    }

    private static boolean hasValue(String[] parts, int i) {
        return parts.length > i && !parts[i].trim().isEmpty();
    }

    private static Integer intAt(String[] parts, int i) {
        return hasValue(parts, i) ? Integer.valueOf(parts[i].trim()) : null;
    }

    private static Double doubleAt(String[] parts, int i) {
        return hasValue(parts, i) ? Double.valueOf(parts[i].trim()) : null;
    }
}
