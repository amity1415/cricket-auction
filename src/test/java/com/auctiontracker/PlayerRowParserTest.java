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

/**
 * CSV/row parsing — focused on the header-aware layout and the new
 * {@code Image_location} column. The standard columns' header path is also
 * covered end-to-end by {@link SetupServiceTest}; the legacy headerless
 * positional layout is pinned here so it stays backward compatible.
 */
class PlayerRowParserTest {

    private final PlayerRowParser parser = new PlayerRowParser(RuleBook.fixed(TestFixtures.props()));

    private static final String LINK = "https://drive.google.com/file/d/15xG_UEe4D9gncKbOj-ftValJLqAL5iub/view?usp=sharing";
    private static final String ID = "15xG_UEe4D9gncKbOj-ftValJLqAL5iub";

    @Test
    void headerWithImageLocationSetsPhotoRefAndKeepsOtherFields() {
        String csv = """
                name,role,category,basePrice,Image_location
                Mithun Das,BATSMAN,B,80000,%s
                """.formatted(LINK);

        List<Player> players = parser.parseCsv(csv);

        assertEquals(1, players.size());
        Player p = players.get(0);
        assertEquals("Mithun Das", p.getName());
        assertEquals(PlayerRole.BATSMAN, p.getRole());
        assertEquals(B, p.getCategory());
        assertEquals(80000, p.getBasePrice());
        assertEquals(ID, p.getPhotoFileId());   // share link normalised to the file id
    }

    @Test
    void columnsMayAppearInAnyOrderAndImageIsOptionalPerRow() {
        String csv = """
                Image_location,category,name,role
                %s,B,With Photo,BOWLER
                ,B,No Photo,BATSMAN
                """.formatted(ID);   // a bare file id is accepted as-is

        List<Player> players = parser.parseCsv(csv);

        assertEquals(2, players.size());
        assertEquals("With Photo", players.get(0).getName());
        assertEquals(ID, players.get(0).getPhotoFileId());
        assertNull(players.get(1).getPhotoFileId());   // blank Image_location → no image
    }

    @Test
    void headerlessLegacyLayoutStillParsesPositionallyWithoutImage() {
        List<Player> players = parser.parseCsv("Ravi,BATSMAN,B\nSam,BOWLER,C");
        assertEquals(2, players.size());
        assertEquals("Ravi", players.get(0).getName());
        assertNull(players.get(0).getPhotoFileId());
    }

    @Test
    void toPhotoRefNormalisesEveryAcceptedForm() {
        assertEquals(ID, PlayerRowParser.toPhotoRef(LINK));
        assertEquals(ID, PlayerRowParser.toPhotoRef("https://drive.google.com/open?id=" + ID));
        assertEquals(ID, PlayerRowParser.toPhotoRef("https://drive.google.com/uc?export=download&id=" + ID));
        assertEquals(ID, PlayerRowParser.toPhotoRef("  " + ID + "  "));                 // bare id, trimmed
        assertEquals("https://cdn.example.com/p/7.jpg",
                PlayerRowParser.toPhotoRef("https://cdn.example.com/p/7.jpg"));          // direct URL kept
        assertNull(PlayerRowParser.toPhotoRef(""));
        assertNull(PlayerRowParser.toPhotoRef(null));
    }

    @Test
    void badRowInHeaderModeIsReportedByLineNumber() {
        String csv = """
                name,role,category,Image_location
                Good,BATSMAN,B,%s
                Bad,NOT_A_ROLE,B,
                """.formatted(ID);
        var ex = assertThrows(RuntimeException.class, () -> parser.parseCsv(csv));
        // The whole import is rejected; the offending line is named.
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("line 3"));
    }
}
