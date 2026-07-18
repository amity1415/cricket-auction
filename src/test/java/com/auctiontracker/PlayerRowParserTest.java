package com.auctiontracker;

import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerRole;
import com.auctiontracker.core.PlayerRowParser;
import com.auctiontracker.tournament.RuleBook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.auctiontracker.core.PlayerCategory.B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV/row parsing — focused on the header-aware layout and the
 * {@code Image_location} column, which names the Google Drive FOLDER holding the
 * posters (each player's image is the file named by its serial inside it). The
 * standard columns' header path is also covered end-to-end by
 * {@link SetupServiceTest}; the legacy headerless positional layout is pinned
 * here so it stays backward compatible.
 */
class PlayerRowParserTest {

    private final PlayerRowParser parser = new PlayerRowParser(RuleBook.fixed(TestFixtures.props()));

    private static final String FOLDER_ID = "1Mduu7FXT7CasAoY9EfC78ThEnZxeV2FX";
    private static final String FOLDER_LINK = "https://drive.google.com/drive/folders/" + FOLDER_ID;

    @Test
    void headerWithImageLocationCapturesFolderNotFileAndKeepsOtherFields() {
        String csv = """
                name,role,category,basePrice,Image_location
                Mithun Das,BATSMAN,B,80000,%s
                """.formatted(FOLDER_LINK);

        List<Player> players = parser.parseCsv(csv);

        assertEquals(1, players.size());
        Player p = players.get(0);
        assertEquals("Mithun Das", p.getName());
        assertEquals(PlayerRole.BATSMAN, p.getRole());
        assertEquals(B, p.getCategory());
        assertEquals(80000, p.getBasePrice());
        assertEquals(FOLDER_ID, p.getPhotoFolderId());   // folder captured for later resolution
        assertNull(p.getPhotoFileId());                  // the actual image is resolved after import
    }

    @Test
    void columnsMayAppearInAnyOrderAndFolderIsOptionalPerRow() {
        String csv = """
                Image_location,category,name,role
                %s,B,With Folder,BOWLER
                ,B,No Folder,BATSMAN
                """.formatted(FOLDER_ID);   // a bare folder id is accepted as-is

        List<Player> players = parser.parseCsv(csv);

        assertEquals(2, players.size());
        assertEquals("With Folder", players.get(0).getName());
        assertEquals(FOLDER_ID, players.get(0).getPhotoFolderId());
        assertNull(players.get(1).getPhotoFolderId());   // blank Image_location → no folder
    }

    @Test
    void headerlessLegacyLayoutStillParsesPositionallyWithoutImage() {
        List<Player> players = parser.parseCsv("Ravi,BATSMAN,B\nSam,BOWLER,C");
        assertEquals(2, players.size());
        assertEquals("Ravi", players.get(0).getName());
        assertNull(players.get(0).getPhotoFolderId());
    }

    @Test
    void toPhotoFolderIdNormalisesEveryAcceptedForm() {
        assertEquals(FOLDER_ID, PlayerRowParser.toPhotoFolderId(FOLDER_LINK));
        assertEquals(FOLDER_ID, PlayerRowParser.toPhotoFolderId(
                "https://drive.google.com/drive/u/0/folders/" + FOLDER_ID));
        assertEquals(FOLDER_ID, PlayerRowParser.toPhotoFolderId(
                "https://drive.google.com/open?id=" + FOLDER_ID));
        assertEquals(FOLDER_ID, PlayerRowParser.toPhotoFolderId("  " + FOLDER_ID + "  "));  // bare id
        assertNull(PlayerRowParser.toPhotoFolderId("https://example.com/not-a-drive-folder"));
        assertNull(PlayerRowParser.toPhotoFolderId(""));
        assertNull(PlayerRowParser.toPhotoFolderId(null));
    }

    @Test
    void badRowInHeaderModeIsReportedByLineNumber() {
        String csv = """
                name,role,category,Image_location
                Good,BATSMAN,B,%s
                Bad,NOT_A_ROLE,B,
                """.formatted(FOLDER_ID);
        var ex = assertThrows(RuntimeException.class, () -> parser.parseCsv(csv));
        assertTrue(ex.getMessage().contains("line 3"));   // whole import rejected, offending line named
    }
}
