package com.auctiontracker.core;

import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tabular player rows into {@link Player}s — shared by the CSV bulk
 * import and the .xlsx setup import.
 *
 * <p>Two layouts are accepted:
 * <ul>
 *   <li><b>With a header row</b> (first cell is {@code name}, case/spacing
 *       insensitive): columns are matched <em>by name</em>, so they can appear in
 *       any order and optional ones can be omitted. Recognised headers:
 *       {@code name, role, category, basePrice, matches, runs, battingAvg,
 *       strikeRate, wickets, economy} and {@code Image_location} — the player's
 *       poster (a Google Drive share link, a bare Drive file id, or a direct
 *       image URL; see {@link #toPhotoRef}).</li>
 *   <li><b>Without a header</b>: the legacy fixed order
 *       {@code name,role,category[,basePrice][,matches,runs,battingAvg,strikeRate,wickets,economy]}
 *       (no image column — add a header row to use {@code Image_location}).</li>
 * </ul>
 * basePrice blank = the category's configured default; stats blank = not shown.
 * All-or-nothing: any bad row rejects the whole import with every error listed.
 */
@Component
public class PlayerRowParser {

    /** One raw row plus its 1-based position in the source file (for error messages). */
    public record Row(int number, String[] fields) {}

    /**
     * Header cell (normalised: lower-cased, non-alphanumerics stripped) → the
     * canonical field it feeds. Multiple spellings can map to the same field.
     */
    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("name", "name"),
            Map.entry("role", "role"),
            Map.entry("category", "category"), Map.entry("group", "category"),
            Map.entry("baseprice", "baseprice"), Map.entry("base", "baseprice"),
            Map.entry("matches", "matches"),
            Map.entry("runs", "runs"),
            Map.entry("battingavg", "battingavg"), Map.entry("battingaverage", "battingavg"),
            Map.entry("avg", "battingavg"),
            Map.entry("strikerate", "strikerate"), Map.entry("sr", "strikerate"),
            Map.entry("wickets", "wickets"),
            Map.entry("economy", "economy"), Map.entry("economyrate", "economy"),
            Map.entry("econ", "economy"),
            Map.entry("imagelocation", "image"), Map.entry("image", "image"),
            Map.entry("imageurl", "image"), Map.entry("photo", "image"),
            Map.entry("photourl", "image"), Map.entry("photolocation", "image"));

    /** File id inside a Google Drive share link (…/file/d/ID, …/d/ID, ?id=ID). */
    private static final Pattern DRIVE_ID =
            Pattern.compile("(?:/d/|/file/d/|[?&]id=)([A-Za-z0-9_-]{20,})");

    private final RuleBook ruleBook;

    public PlayerRowParser(RuleBook ruleBook) {
        this.ruleBook = ruleBook;
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
        Map<String, Integer> columns = null;   // non-null once a header row is seen → parse by name
        boolean first = true;
        for (Row row : rows) {
            if (first) {
                first = false;
                // Row 1 is a header when it names the three required columns (in
                // any order) — data rows never spell out name/role/category. A
                // header lets Image_location and the rest sit in any position;
                // without one we fall back to the legacy fixed order.
                Map<String, Integer> candidate = headerColumns(row.fields());
                if (candidate.containsKey("name") && candidate.containsKey("role")
                        && candidate.containsKey("category")) {
                    columns = candidate;
                    continue;   // skip the header row itself
                }
            }
            try {
                parsed.add(columns != null ? parseByHeader(row.fields(), columns)
                                           : parseFields(row.fields()));
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

    // --- Header-mapped parsing (columns located by name) --------------------

    private Map<String, Integer> headerColumns(String[] header) {
        Map<String, Integer> cols = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            String canonical = HEADER_ALIASES.get(normalizeHeader(header[i]));
            if (canonical != null) cols.putIfAbsent(canonical, i); // first column of each kind wins
        }
        return cols;
    }

    private Player parseByHeader(String[] parts, Map<String, Integer> cols) {
        String name = required(parts, cols, "name");
        PlayerRole role = PlayerRole.valueOf(required(parts, cols, "role").toUpperCase(Locale.ROOT));
        PlayerCategory category =
                PlayerCategory.valueOf(required(parts, cols, "category").toUpperCase(Locale.ROOT));
        String bp = value(parts, cols, "baseprice");
        long basePrice = bp != null ? Long.parseLong(bp) : ruleBook.current().basePriceFor(category);
        if (basePrice <= 0) throw new IllegalArgumentException("base price must be positive");

        Player player = Player.register(name, role, category, basePrice);
        PlayerStats stats = new PlayerStats(
                intValue(parts, cols, "matches"), intValue(parts, cols, "runs"),
                doubleValue(parts, cols, "battingavg"), doubleValue(parts, cols, "strikerate"),
                intValue(parts, cols, "wickets"), doubleValue(parts, cols, "economy"));
        player.setStats(stats.allNull() ? null : stats);
        player.setPhotoFileId(toPhotoRef(value(parts, cols, "image")));
        return player;
    }

    private static String value(String[] parts, Map<String, Integer> cols, String key) {
        Integer i = cols.get(key);
        if (i == null || i >= parts.length) return null;
        String v = parts[i].trim();
        return v.isEmpty() ? null : v;
    }

    private static String required(String[] parts, Map<String, Integer> cols, String key) {
        String v = value(parts, cols, key);
        if (v == null) throw new IllegalArgumentException(key + " is blank");
        return v;
    }

    private static Integer intValue(String[] parts, Map<String, Integer> cols, String key) {
        String v = value(parts, cols, key);
        return v == null ? null : Integer.valueOf(v);
    }

    private static Double doubleValue(String[] parts, Map<String, Integer> cols, String key) {
        String v = value(parts, cols, key);
        return v == null ? null : Double.valueOf(v);
    }

    // --- Legacy positional parsing (no header row) --------------------------

    private Player parseFields(String[] parts) {
        if (parts.length < 3) {
            throw new IllegalArgumentException(
                    "expected name,role,category[,basePrice,matches,runs,battingAvg,strikeRate,wickets,economy]");
        }
        String name = parts[0].trim();
        if (name.isEmpty()) throw new IllegalArgumentException("name is blank");
        PlayerRole role = PlayerRole.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
        PlayerCategory category = PlayerCategory.valueOf(parts[2].trim().toUpperCase(Locale.ROOT));
        long basePrice = hasValue(parts, 3) ? Long.parseLong(parts[3].trim()) : ruleBook.current().basePriceFor(category);
        if (basePrice <= 0) throw new IllegalArgumentException("base price must be positive");

        Player player = Player.register(name, role, category, basePrice);
        PlayerStats stats = new PlayerStats(
                intAt(parts, 4), intAt(parts, 5), doubleAt(parts, 6),
                doubleAt(parts, 7), intAt(parts, 8), doubleAt(parts, 9));
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

    // --- Image location ----------------------------------------------------

    /**
     * Normalise an {@code Image_location} cell into the reference stored on
     * {@link Player#getPhotoFileId()} and later resolved by the photo service:
     * <ul>
     *   <li>a Google Drive share link (…/file/d/ID/view, ?id=ID, …) → its file id;</li>
     *   <li>a bare Drive file id → used as-is;</li>
     *   <li>any other http(s) URL → kept whole as a direct image link.</li>
     * </ul>
     * Blank / null → null (the player simply has no image). Static so the value
     * can be normalised without a Spring bean.
     */
    public static String toPhotoRef(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("http://") || s.startsWith("https://")) {
            Matcher m = DRIVE_ID.matcher(s);
            return m.find() ? m.group(1) : s;   // Drive link → id; otherwise a direct URL
        }
        return s;                                // assume a bare Drive file id
    }

    private static String normalizeHeader(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
