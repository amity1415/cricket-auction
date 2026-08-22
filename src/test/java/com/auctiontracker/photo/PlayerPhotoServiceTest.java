package com.auctiontracker.photo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing of Google Drive's public embedded-folder-view HTML into the
 * {@code serial -> fileId} map. Uses a captured copy of the real photo folder's
 * listing (56 files named 1…56) so the regex stays honest against Drive's actual
 * markup — no network. The serial→player mapping and cache warm-up are exercised
 * end-to-end by the running app; here we pin the brittle scraping step.
 */
class PlayerPhotoServiceTest {

    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/photo/embedded-folder-view.html")) {
            assertThat(in).as("test fixture present").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesEverySerialToItsFileId() throws Exception {
        Map<String, String> map = PlayerPhotoService.parseFolderListing(fixture());

        // The folder holds exactly the 56 serial-numbered posters, 1 through 56.
        assertThat(map).hasSize(56);
        for (int serial = 1; serial <= 56; serial++) {
            assertThat(map).containsKey(String.valueOf(serial));
        }

        // File ids are real Drive ids (the long opaque handle used to download),
        // distinct per serial, and match the known ids for a couple of anchors.
        assertThat(map.get("1")).isEqualTo("15xG_UEe4D9gncKbOj-ftValJLqAL5iub");
        assertThat(map.get("30")).isEqualTo("1AimGMP95ybVdeJskT1nsTJd_hUyLaRe_");
        assertThat(map.values()).doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    void ignoresNonNumericTitlesAndEmptyHtml() {
        assertThat(PlayerPhotoService.parseFolderListing("")).isEmpty();
        assertThat(PlayerPhotoService.parseFolderListing(
                "<div class=\"flip-entry\" id=\"entry-ABC123\">"
                        + "<div class=\"flip-entry-title\">README</div></div>"))
                .isEmpty();
    }

    /**
     * Drive's embedded view renders some folders' titles WITH the file extension
     * ("1.png", "10.jpeg"). The serial must still resolve — a single trailing
     * extension is stripped before the numeric match, while a genuinely
     * non-numeric base ("cover.jpg") is still ignored.
     */
    @Test
    void parsesSerialsThatKeepTheirFileExtension() {
        String html =
                entry("FID_ONE", "1.png")
                        + entry("FID_TEN", "10.jpeg")
                        + entry("FID_HUND", "100.JPG")
                        + entry("FID_PLAIN", "7")          // extension-less still works
                        + entry("FID_COVER", "cover.jpg"); // non-numeric base ignored
        Map<String, String> map = PlayerPhotoService.parseFolderListing(html);

        assertThat(map).hasSize(4);
        assertThat(map.get("1")).isEqualTo("FID_ONE");
        assertThat(map.get("10")).isEqualTo("FID_TEN");
        assertThat(map.get("100")).isEqualTo("FID_HUND");
        assertThat(map.get("7")).isEqualTo("FID_PLAIN");
        assertThat(map).doesNotContainKey("cover");
    }

    private static String entry(String fileId, String title) {
        return "<div class=\"flip-entry\" id=\"entry-" + fileId + "\">"
                + "<div class=\"flip-entry-title\">" + title + "</div></div>";
    }
}
