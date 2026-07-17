package com.auctiontracker.photo;

import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerJpaRepository;
import com.auctiontracker.tournament.Tournament;
import com.auctiontracker.tournament.TournamentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves each player's poster image from a public Google Drive folder.
 *
 * <p>The folder holds one image per player, named by the player's 1-based import
 * serial (1, 2, 3, …) — that is, the order the player appears in the imported
 * file, which the app already records as {@link Player#getSeq()} (0-based, so
 * serial = seq + 1). At startup we:
 * <ol>
 *   <li>list the folder via Drive's public {@code embeddedfolderview} HTML (no
 *       API key needed for an "anyone with the link" folder), parsing a
 *       {@code serial -> Drive file id} map;</li>
 *   <li>stamp each player of the configured tournament with the file id whose
 *       serial matches its {@code seq + 1}, persisting it on the player row;</li>
 *   <li>warm an in-memory {@code playerId -> bytes} cache by downloading each
 *       image once, so serving never hits Drive on the hot path.</li>
 * </ol>
 *
 * <p>All of this runs asynchronously after the context is ready and is wrapped
 * so it can never delay or crash startup — if Drive is unreachable the players
 * simply render with their initials, exactly as before. A cache miss (e.g. a
 * file id set on a row but not yet warmed) is downloaded lazily on first request.
 */
@Service
public class PlayerPhotoService {

    private static final Logger log = LoggerFactory.getLogger(PlayerPhotoService.class);

    /**
     * Each folder entry in the embedded-folder HTML looks like
     * {@code <div class="flip-entry" id="entry-<FILE_ID>" …> … <div class="flip-entry-title">1</div>}.
     * Capture the file id and the (numeric) title that follows it.
     */
    private static final Pattern ENTRY = Pattern.compile(
            "id=\"entry-([A-Za-z0-9_-]+)\".*?flip-entry-title\"[^>]*>([^<]+)</div>",
            Pattern.DOTALL);

    private final PlayerJpaRepository players;
    private final TournamentRepository tournaments;

    private final boolean enabled;
    private final String folderId;
    private final String tournamentSlug;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** playerId -> JPEG bytes. Populated at startup and on lazy cache misses. */
    private final Map<UUID, byte[]> cache = new ConcurrentHashMap<>();

    public PlayerPhotoService(PlayerJpaRepository players,
                              TournamentRepository tournaments,
                              @Value("${auction.photos.enabled:true}") boolean enabled,
                              @Value("${auction.photos.folder-id:}") String folderId,
                              @Value("${auction.photos.tournament-slug:}") String tournamentSlug) {
        this.players = players;
        this.tournaments = tournaments;
        this.enabled = enabled;
        this.folderId = folderId == null ? "" : folderId.trim();
        this.tournamentSlug = tournamentSlug == null ? "" : tournamentSlug.trim();
    }

    // --- Startup ------------------------------------------------------------

    /** Kick off folder mapping + cache warm-up off the startup thread. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled || folderId.isEmpty()) {
            log.info("Player photos disabled (enabled={}, folderId={}).", enabled, folderId.isEmpty() ? "unset" : "set");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                syncAndWarm();
            } catch (Exception e) {
                // Never let image plumbing affect the app — just log and move on.
                log.warn("Player photo sync failed; players will render with initials. {}", e.toString());
            }
        });
    }

    /**
     * Re-list the folder, (re)map file ids onto the tournament's players, and warm
     * the byte cache. Safe to call again at runtime (e.g. after adding images);
     * returns the number of players that now have an image mapped.
     */
    public int syncAndWarm() {
        Map<String, String> serialToFileId = listFolder();
        if (serialToFileId.isEmpty()) {
            log.warn("Photo folder {} listed 0 files — nothing to map.", folderId);
            return 0;
        }
        UUID tournamentId = resolveTournamentId();
        if (tournamentId == null) {
            log.warn("Photo tournament '{}' not found — skipping photo mapping.", tournamentSlug);
            return 0;
        }
        List<Player> pool = players.findByTournamentIdOrdered(tournamentId);
        int mapped = 0;
        for (Player p : pool) {
            if (p.getSeq() == null) continue;                    // no import order → no serial
            String fileId = serialToFileId.get(String.valueOf(p.getSeq() + 1));
            if (fileId == null) continue;                        // no image for this serial
            if (!fileId.equals(p.getPhotoFileId())) {
                p.setPhotoFileId(fileId);
                players.save(p);                                 // own transaction per save
            }
            mapped++;
        }
        log.info("Player photos: mapped {}/{} players in '{}' from {} folder files.",
                mapped, pool.size(), tournamentSlug, serialToFileId.size());
        warmCache(pool);
        return mapped;
    }

    private void warmCache(List<Player> pool) {
        int loaded = 0;
        for (Player p : pool) {
            if (!p.hasPhoto() || cache.containsKey(p.getPlayerId())) continue;
            byte[] bytes = download(p.getPhotoFileId());
            if (bytes != null) {
                cache.put(p.getPlayerId(), bytes);
                loaded++;
            }
        }
        log.info("Player photos: warmed {} images into memory cache.", loaded);
    }

    // --- Serving ------------------------------------------------------------

    /**
     * The player's poster bytes, or {@code null} if the player has no image (or it
     * couldn't be fetched). Served from memory; a miss is downloaded once and cached.
     */
    public byte[] photo(UUID playerId) {
        byte[] cached = cache.get(playerId);
        if (cached != null) return cached;
        if (!enabled) return null;
        String fileId = players.findById(playerId).map(Player::getPhotoFileId).orElse(null);
        if (fileId == null || fileId.isBlank()) return null;
        byte[] bytes = download(fileId);
        if (bytes != null) cache.put(playerId, bytes);
        return bytes;
    }

    // --- Drive access -------------------------------------------------------

    /** Fetch and parse {@code serial -> fileId} from the public embedded-folder listing. */
    private Map<String, String> listFolder() {
        String url = "https://drive.google.com/embeddedfolderview?id=" + folderId + "#list";
        String html = fetchString(url);
        return html == null ? new HashMap<>() : parseFolderListing(html);
    }

    /**
     * Parse a Drive embedded-folder-view HTML page into {@code serial -> fileId}.
     * Each file's title is its serial number; the extension isn't shown. Only
     * clean numeric titles are kept and normalised (e.g. "01" -> "1"); on a
     * duplicate serial the last entry wins. Package-visible for unit testing.
     */
    static Map<String, String> parseFolderListing(String html) {
        Map<String, String> map = new HashMap<>();
        Matcher m = ENTRY.matcher(html);
        while (m.find()) {
            String fileId = m.group(1);
            String title = m.group(2).trim();
            if (title.matches("\\d+")) {
                map.put(String.valueOf(Integer.parseInt(title)), fileId);
            }
        }
        return map;
    }

    /** Download a Drive file's bytes, or null on any failure / non-image response. */
    private byte[] download(String fileId) {
        String url = "https://drive.google.com/uc?export=download&id=" + fileId;
        try {
            HttpResponse<byte[]> res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String type = res.headers().firstValue("content-type").orElse("");
            if (res.statusCode() == 200 && type.startsWith("image/")) {
                return res.body();
            }
            log.warn("Photo download {} -> HTTP {} ({})", fileId, res.statusCode(), type);
        } catch (Exception e) {
            log.warn("Photo download {} failed: {}", fileId, e.toString());
        }
        return null;
    }

    private String fetchString(String url) {
        try {
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) return res.body();
            log.warn("Photo folder listing {} -> HTTP {}", url, res.statusCode());
        } catch (Exception e) {
            log.warn("Photo folder listing failed: {}", e.toString());
        }
        return null;
    }

    private UUID resolveTournamentId() {
        Optional<Tournament> t = tournamentSlug.isEmpty()
                ? tournaments.findFirstByActiveTrue()
                : tournaments.findBySlug(tournamentSlug);
        return t.map(Tournament::getId).orElse(null);
    }
}
