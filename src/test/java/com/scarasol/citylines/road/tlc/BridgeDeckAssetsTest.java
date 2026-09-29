package com.scarasol.citylines.road.tlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.scarasol.citylines.road.core.RoadType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the generated Citylines bridge deck asset.
 *
 * <p>{@code Bridges.generateBridge} places one 16x16 part per span chunk and reads it
 * as {@code getPaletteChar(x, l, z)} for {@code Orientation.X} and as
 * {@code getPaletteChar(z, l, x)} for {@code Orientation.Z} - a transpose. The deck is
 * therefore authored so that its cross-section is constant along the road axis:
 *
 * <ul>
 *   <li>slice 0 is exactly the arterial (PRIMARY) section - walk 1 + kerb 1 + carriageway 12
 *       with its two centre-line cells + kerb 1 + walk 1 - read along the resource's own row
 *       axis;</li>
 *   <li>the two edges perpendicular to the road axis present that section in both
 *       orientations, so the deck port matches the Citylines PRIMARY street port cell for cell
 *       where it meets the shore (the deck is placed at {@code profile.GROUNDLEVEL},
 *       the Citylines street sits at {@code getCityGroundLevel() == GROUNDLEVEL + level*6},
 *       and bridge shores are level-0 chunks by construction);</li>
 *   <li>the two long edges are the outer walk band;</li>
 *   <li>slice 1 carries the railings above those two walk rows, so the deck keeps its
 *       full arterial surface (walk 1 + kerb 1 + carriageway 12 + kerb 1 + walk 1). Lost Cities stacks the slices upward, exactly like
 *       its own multi-slice bridge parts ({@code bridge_large_open} has four);</li>
 *   <li>the deck defines no support metadata, so Lost Cities falls back to the
 *       CityStyle/WorldStyle bridge support (the Citylines chain keeps
 *       {@code bridgesupport} from the built-in standard world style).</li>
 * </ul>
 */
class BridgeDeckAssetsTest {

    private static final Path ASSETS = Path.of("src", "main", "resources", "data", "citylines", "lostcities");
    private static final Path DECK = ASSETS.resolve("parts/bridge_deck.json");
    private static final String DECK_ID = "citylines:bridge_deck";
    /**
     * Derived from the layout instead of hardcoded: the deck has to present the arterial
     * cross-section cell for cell, whatever that section currently is (decision D4 widened it and
     * added the two-cell centre line).
     */
    private static final String PRIMARY_SECTION = section(RoadType.PRIMARY);
    private static final String WALK_ROW = "F".repeat(16);

    /** The edge role string of a class, as palette characters. */
    private static String section(RoadType type) {
        StringBuilder text = new StringBuilder();
        for (byte role : RoadSurfaceLayout.sectionProfile(type)) {
            text.append(RoadSurfaceLayout.character(role));
        }
        return text.toString();
    }

    @Test
    @DisplayName("the deck is a two-slice 16x16 part whose surface is the PRIMARY section")
    void deckSurfaceIsThePrimarySection() {
        Map<String, Object> doc = document(DECK);
        assertEquals(16, TestJson.intValue(doc, "xsize"));
        assertEquals(16, TestJson.intValue(doc, "zsize"));
        assertEquals(RoadPartTable.PALETTE, doc.get("refpalette"));
        List<Object> slices = TestJson.array(doc.get("slices"));
        assertEquals(2, slices.size(), "surface + railing slice");

        List<String> surface = rows(TestJson.array(slices.get(0)));
        StringBuilder firstColumn = new StringBuilder();
        for (int z = 0; z < 16; z++) {
            final int fz = z;
            String row = surface.get(z);
            assertEquals(16, row.length(), "row " + z + " length");
            assertEquals(1, (int) row.chars().distinct().count(),
                    () -> "row " + fz + " must be constant along the road axis: " + surface.get(fz));
            assertEquals(PRIMARY_SECTION.charAt(z), row.charAt(0),
                    "cross-section cell " + z + " must be the PRIMARY section");
            firstColumn.append(row.charAt(0));
        }
        assertEquals(PRIMARY_SECTION, firstColumn.toString(), "the cross-section column");
    }

    @Test
    @DisplayName("both orientations present the PRIMARY section at the shore, cell for cell")
    void deckPortsAreFlushWithThePrimaryStreetInBothOrientations() {
        List<String> surface = slices(document(DECK)).get(0);
        // Orientation.X reads the resource as authored: the shore seam is the W/E edge.
        assertEquals(PRIMARY_SECTION, edge(surface, "W"), "X deck west edge");
        assertEquals(PRIMARY_SECTION, edge(surface, "E"), "X deck east edge");
        assertEquals(WALK_ROW, edge(surface, "N"), "X deck long side");
        assertEquals(WALK_ROW, edge(surface, "S"), "X deck long side");

        // Orientation.Z reads the transpose, so the shore seam moves to the N/S edge.
        List<String> transposed = transpose(surface);
        assertEquals(PRIMARY_SECTION, edge(transposed, "N"), "Z deck north edge");
        assertEquals(PRIMARY_SECTION, edge(transposed, "S"), "Z deck south edge");
        assertEquals(WALK_ROW, edge(transposed, "W"), "Z deck long side");
        assertEquals(WALK_ROW, edge(transposed, "E"), "Z deck long side");
    }

    @Test
    @DisplayName("railings sit above the two outer walk rows and survive the transpose")
    void railingsStandOnTheOuterWalkRows() {
        List<List<String>> deckSlices = slices(document(DECK));
        List<String> surface = deckSlices.get(0);
        List<String> railing = deckSlices.get(1);
        assertEquals("R".repeat(16), railing.get(0));
        assertEquals("R".repeat(16), railing.get(15));
        for (int z = 1; z < 15; z++) {
            assertEquals(" ".repeat(16), railing.get(z), "railing slice row " + z + " must be empty");
        }
        assertTrue(surface.get(0).startsWith("F") && surface.get(15).startsWith("F"),
                "the railings must stand on walk cells, not on the carriageway");
        assertTrue(surface.stream().noneMatch(row -> row.indexOf('R') >= 0),
                "the road surface slice must contain no railing block");

        // Z orientation: railing rows become columns 0 and 15, the two long sides.
        List<String> transposed = transpose(railing);
        for (int z = 0; z < 16; z++) {
            assertEquals('R', transposed.get(z).charAt(0), "Z railing west side at row " + z);
            assertEquals('R', transposed.get(z).charAt(15), "Z railing east side at row " + z);
        }
    }

    @Test
    @DisplayName("the deck defines its railing locally and leaves the street palette alone")
    void deckPaletteAndChain() {
        Map<String, Object> doc = document(DECK);
        Map<Character, String> local = new LinkedHashMap<>();
        for (Object element : TestJson.array(doc.get("palette"))) {
            Map<String, Object> entry = TestJson.object(element);
            local.put(((String) entry.get("char")).charAt(0), (String) entry.get("block"));
        }
        assertEquals(Map.of('R', "minecraft:iron_bars"), local,
                "the deck owns exactly its railing char; the street palette stays unchanged");
        assertFalse(doc.containsKey("meta"), "no support metadata: TLC must use the CityStyle/WorldStyle support");

        Map<String, Object> citystyle = document(ASSETS.resolve("citystyles/standard.json"));
        List<Object> largeBridges = TestJson.array(TestJson.object(citystyle.get("selectors")).get("largebridges"));
        assertEquals(1, largeBridges.size());
        assertEquals(DECK_ID, TestJson.object(largeBridges.get(0)).get("value"),
                "the Citylines citystyle must select the deck in largebridges");

        Map<String, Object> worldstyle = document(ASSETS.resolve("worldstyles/standard.json"));
        assertTrue(worldstyle.get("bridgesupport") instanceof String,
                "the world style must keep a bridge support block for the deck's supports");

        assertFalse(RoadPartTable.partIds().contains(DECK_ID),
                "the deck is not a street piece and must not enter the street placement table");
    }

    // -------------------------------------------------------------- helpers ----

    @SuppressWarnings("unchecked")
    private static List<List<String>> slices(Map<String, Object> doc) {
        List<List<String>> slices = new ArrayList<>();
        for (Object slice : TestJson.array(doc.get("slices"))) {
            slices.add(rows(TestJson.array(slice)));
        }
        return slices;
    }

    private static List<String> rows(List<Object> rows) {
        List<String> result = new ArrayList<>();
        for (Object row : rows) {
            result.add((String) row);
        }
        return result;
    }

    /** The grid Orientation.Z reads: part {@code (z, x)} at world {@code (x, z)}. */
    private static List<String> transpose(List<String> grid) {
        List<String> result = new ArrayList<>();
        for (int z = 0; z < 16; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < 16; x++) {
                row.append(grid.get(x).charAt(z));
            }
            result.add(row.toString());
        }
        return result;
    }

    private static String edge(List<String> grid, String direction) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(switch (direction) {
                case "N" -> grid.get(0).charAt(i);
                case "S" -> grid.get(15).charAt(i);
                case "W" -> grid.get(i).charAt(0);
                default -> grid.get(i).charAt(15);
            });
        }
        return sb.toString();
    }

    private static Map<String, Object> document(Path file) {
        assertTrue(Files.isRegularFile(file), "missing asset: " + file.toAbsolutePath());
        try {
            return TestJson.object(TestJson.read(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
