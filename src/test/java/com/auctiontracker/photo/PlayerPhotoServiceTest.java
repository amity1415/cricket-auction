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
}
