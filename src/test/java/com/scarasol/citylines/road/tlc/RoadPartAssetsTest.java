package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.RoadType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven contract test for the generated Citylines road assets.
 *
 * <p>It reads the committed JSON from {@code src/main/resources} (no game, no
 * Minecraft types) and checks the Stage-3 gate:
 *
 * <ul>
 *   <li>every part the table references exists, has {@code xsize}/{@code zsize} 16 and
 *       exactly one slice;</li>
 *   <li>the cells match the frozen section model ({@link RoadSurfaceLayout}), so the
 *       JSON cannot drift from the geometry the renderer's decoration checks use;</li>
 *   <li><b>port contract:</b> for every one of the 110 directed keys and every direction,
 *       the rotated piece presents exactly the section of that shared edge class
 *       ({@code NONE} = 16 walk, {@code SECONDARY} = 3W+1K+8P+1K+3W,
 *       {@code PRIMARY} = 2W+1K+10P+1K+2W) — which is also what the neighbouring piece
 *       of the same edge class presents on the opposite edge, checked pairwise;</li>
 *   <li>a {@code NONE} edge never leaks a carriageway cell;</li>
 *   <li>the piece's own road class stays visible in the middle, so a PRIMARY centre
 *       with collector arms is not the collector piece;</li>
 *   <li>the palette/damage/style chain carries the material and damage specification.</li>
 * </ul>
 *
 * <p>The all-collector-arm shapes are also required to differ between a PRIMARY and a
 * SECONDARY centre (the frozen design: "同样四个集散接入口可以是普通集散路口，也可以是
 * 中间仍为主干、向四周收窄的主干路口").
 */
class RoadPartAssetsTest {

    private static final Path ASSETS = Path.of("src", "main", "resources", "data", "citylines", "lostcities");
    private static final Path PARTS = ASSETS.resolve("parts");

    /**
     * The Citylines bridge deck lives in the same directory but is not a street piece: it is a
     * two-slice part (road surface + railing) and has its own contract in
     * {@code BridgeDeckAssetsTest}. Every other file in the directory must still be a
     * single-slice street piece referenced by {@link RoadPartTable}.
     */
    private static final String BRIDGE_DECK_FILE = "bridge_deck";

    private static final List<String> COLLECTOR_ONLY_SHAPES = List.of("000s", "00ss", "0s0s", "0sss", "ssss");

    /** One directed selection key. */
    private record Key(RoadType centre, RoadType n, RoadType e, RoadType s, RoadType w) {

        RoadType edge(Direction direction) {
            return switch (direction) {
                case N -> n;
                case E -> e;
                case S -> s;
                case W -> w;
            };
        }

        /**
         * Human-readable key. A local centre has no centre letter in the asset name -- its file is
         * {@code road_t_<four arms>}, because {@code t} already denotes the local class in the arm
         * alphabet -- so the centre letter is omitted for {@code TERTIARY} to keep this signature
         * aligned with the part name it refers to.
         */
        String signature() {
            String centreLetter = centre == RoadType.TERTIARY ? "" : text(centre);
            return centreLetter + text(n) + text(e) + text(s) + text(w);
        }
    }

    private static Map<String, byte[]> parts;

    private static synchronized Map<String, byte[]> parts() {
        if (parts == null) {
            parts = loadParts();
        }
        return parts;
    }

    // ------------------------------------------------------------- file shape ---

    @Test
    void everyReferencedPartExistsExactlyOnceAsASingleSlice16x16() {
        Map<String, byte[]> loaded = parts();
        assertEquals(RoadPartTable.partIds().size(), loaded.size(),
                "one part file per table id; files on disk: " + loaded.keySet());
        for (String id : RoadPartTable.partIds()) {
            assertTrue(loaded.containsKey(stem(id)), "missing part file for " + id);
        }
        for (String file : loaded.keySet()) {
            assertTrue(RoadPartTable.partIds().contains(RoadPartTable.NAMESPACE + ":" + file),
                    "unexpected part file not referenced by the table: " + file);
        }
    }

    // -------------------------------------------------------------- geometry ----

    @Test
    void everyKeyPresentsThePureSectionOfItsEdgeClass() {
        List<String> failures = new ArrayList<>();
        for (Key key : keys()) {
            byte[] grid = placed(key);
            for (Direction direction : Direction.VALUES) {
                RoadType edgeClass = key.edge(direction);
                byte[] actual = RoadSurfaceLayout.edgeProfile(grid, direction);
                byte[] expected = RoadSurfaceLayout.sectionProfile(edgeClass);
                if (!java.util.Arrays.equals(actual, expected)) {
                    failures.add(key.signature() + " " + direction + " -> "
                            + roleString(actual) + " want " + roleString(expected)
                            + " (edge class " + edgeClass + ")");
                }
            }
        }
        assertTrue(failures.isEmpty(), "edge ports must be the pure section of the shared edge class:\n"
                + String.join("\n", failures));
    }

    @Test
    void placedPieceMatchesTheSectionModelCellForCell() {
        Map<String, String> failures = new TreeMap<>();
        for (Key key : keys()) {
            // RoadSurfaceLayout.grid already returns the piece in world orientation for
            // this key; the table's rotation only maps the canonical JSON onto it.
            byte[] expected = RoadSurfaceLayout.grid(key.centre(), key.n(), key.e(), key.s(), key.w());
            byte[] actual = placed(key);
            if (!java.util.Arrays.equals(actual, expected)) {
                failures.put(key.signature(), describe(actual, expected));
            }
        }
        assertTrue(failures.isEmpty(), "generated JSON drifted from RoadSurfaceLayout:\n" + failures);
    }

    @Test
    void junctionCentresAreUnmarkedButStraightPrimaryKeepsItsLine() {
        byte[] junction = placed(new Key(RoadType.PRIMARY, RoadType.NONE, RoadType.PRIMARY,
                RoadType.SECONDARY, RoadType.PRIMARY));
        byte[] straight = placed(new Key(RoadType.PRIMARY, RoadType.NONE, RoadType.PRIMARY,
                RoadType.NONE, RoadType.PRIMARY));
        for (int z = RoadSurfaceLayout.bandLo(RoadType.PRIMARY);
             z < RoadSurfaceLayout.bandHi(RoadType.PRIMARY); z++) {
            for (int x = RoadSurfaceLayout.bandLo(RoadType.PRIMARY);
                 x < RoadSurfaceLayout.bandHi(RoadType.PRIMARY); x++) {
                assertFalse(RoadSurfaceLayout.role(junction, x, z) == RoadSurfaceLayout.CENTRE,
                        "junction centre must have no line at " + x + "," + z);
            }
        }
        assertEquals(RoadSurfaceLayout.CENTRE,
                RoadSurfaceLayout.role(straight, RoadSurfaceLayout.centreFrom(), RoadSurfaceLayout.centreFrom()),
                "the same primary axis still has a centre line away from a junction");
        for (Key key : keys()) {
            if (key.centre() != RoadType.PRIMARY) continue;
            boolean northSouth = key.n() == RoadType.PRIMARY && key.s() == RoadType.PRIMARY
                    && (key.e().isRoad() || key.w().isRoad());
            boolean eastWest = key.e() == RoadType.PRIMARY && key.w() == RoadType.PRIMARY
                    && (key.n().isRoad() || key.s().isRoad());
            if (!northSouth && !eastWest) continue;
            byte[] grid = placed(key);
            for (int z = RoadSurfaceLayout.bandLo(RoadType.PRIMARY);
                 z < RoadSurfaceLayout.bandHi(RoadType.PRIMARY); z++) {
                for (int x = RoadSurfaceLayout.bandLo(RoadType.PRIMARY);
                     x < RoadSurfaceLayout.bandHi(RoadType.PRIMARY); x++) {
                    assertFalse(RoadSurfaceLayout.role(grid, x, z) == RoadSurfaceLayout.CENTRE,
                            key.signature() + " junction centre has a line at " + x + "," + z);
                }
            }
        }
    }

    @Test
    void noCarriagewayLeaksThroughAnUnconnectedEdge() {
        List<String> failures = new ArrayList<>();
        for (Key key : keys()) {
            byte[] grid = placed(key);
            for (Direction direction : Direction.VALUES) {
                if (key.edge(direction).isRoad()) {
                    continue;
                }
                byte[] profile = RoadSurfaceLayout.edgeProfile(grid, direction);
                for (byte role : profile) {
                    if (RoadSurfaceLayout.isPaved(role)) {
                        failures.add(key.signature() + " leaks paved surface on its " + direction + " edge");
                        break;
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void neighbouringPiecesAgreeCellForCellOnTheSharedEdge() {
        List<Key> keys = keys();
        List<String> failures = new ArrayList<>();
        for (Key key : keys) {
            byte[] mine = placed(key);
            for (Direction direction : Direction.VALUES) {
                RoadType edgeClass = key.edge(direction);
                byte[] profile = RoadSurfaceLayout.edgeProfile(mine, direction);
                Direction opposite = direction.opposite();
                for (Key neighbour : keys) {
                    if (neighbour.edge(opposite) != edgeClass) {
                        continue;
                    }
                    byte[] other = RoadSurfaceLayout.edgeProfile(placed(neighbour), opposite);
                    if (!java.util.Arrays.equals(profile, other)) {
                        failures.add(key.signature() + " " + direction + " = " + roleString(profile)
                                + " but neighbour " + neighbour.signature() + " " + opposite + " = "
                                + roleString(other));
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "shared edge ports must match cell for cell:\n"
                + String.join("\n", failures));
    }

    @Test
    void everyRotationRealisesItsDirectedKey() {
        // The table stores (canonical part, clockwise quarter turns); rotating the
        // canonical piece by that many Transform quarter turns must reproduce the key.
        for (Key key : keys()) {
            RoadPartTable.Entry entry = RoadPartTable.entryFor(key.centre(), key.n(), key.e(), key.s(), key.w());
            assertNotNull(entry, "no table entry for " + key.signature());
            byte[] canonical = parts().get(stem(entry.part()));
            assertNotNull(canonical, "no file for " + entry.part());
            byte[] placed = RoadSurfaceLayout.rotateCw(canonical, entry.quarterTurns());
            for (Direction direction : Direction.VALUES) {
                assertArrayEquals(RoadSurfaceLayout.sectionProfile(key.edge(direction)),
                        RoadSurfaceLayout.edgeProfile(placed, direction),
                        key.signature() + " -> " + entry.part() + " turn " + entry.quarterTurns()
                                + " " + direction);
            }
        }
    }

    // ---------------------------------------------------------- centre class ----

    @Test
    void centreRoadClassStaysVisibleInTheMiddleOfThePiece() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : parts().entrySet()) {
            String name = entry.getKey();
            byte[] grid = entry.getValue();
            RoadType centre = switch (name.charAt("road_".length())) {
                case 'p' -> RoadType.PRIMARY;
                case 's' -> RoadType.SECONDARY;
                case 't' -> RoadType.TERTIARY;
                default -> throw new AssertionError("unknown centre letter in " + name);
            };
            int lo = RoadSurfaceLayout.bandLo(centre);
            int hi = RoadSurfaceLayout.bandHi(centre);
            for (int z = lo; z < hi; z++) {
                for (int x = lo; x < hi; x++) {
                    byte role = RoadSurfaceLayout.role(grid, x, z);
                    // The junction square is the piece's own lane material; on the arterial tier
                    // the two centre-line cells may cross it (a straight-through road keeps its
                    // line, a four-way junction does not).
                    boolean ok = role == RoadSurfaceLayout.lane(centre)
                            || (centre == RoadType.PRIMARY && role == RoadSurfaceLayout.CENTRE);
                    if (!ok) {
                        failures.add(name + " centre cell (" + x + "," + z + ") is "
                                + RoadSurfaceLayout.character(role));
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "the centre cross-section must be carriageway:\n"
                + String.join("\n", failures));
    }

    @Test
    void primaryCentreWithCollectorArmsDiffersFromTheCollectorPiece() {
        for (String shape : COLLECTOR_ONLY_SHAPES) {
            byte[] primary = parts().get("road_p_" + shape);
            byte[] secondary = parts().get("road_s_" + shape);
            assertNotNull(primary, "road_p_" + shape);
            assertNotNull(secondary, "road_s_" + shape);
            assertFalse(java.util.Arrays.equals(primary, secondary),
                    "road_p_" + shape + " must narrow its arterial carriageway towards the collector edges, "
                            + "not be identical to road_s_" + shape);
        }
    }

    @Test
    void isolatedPrimaryCapHasAnArterialCentreAndSealedEdges() {
        byte[] grid = parts().get("road_p_0000");
        assertNotNull(grid, "road_p_0000 must exist");
        int lo = RoadSurfaceLayout.bandLo(RoadType.PRIMARY);
        int hi = RoadSurfaceLayout.bandHi(RoadType.PRIMARY);
        for (int z = lo; z < hi; z++) {
            for (int x = lo; x < hi; x++) {
                assertEquals(RoadSurfaceLayout.CARRIAGEWAY, RoadSurfaceLayout.role(grid, x, z),
                        "cap centre cell (" + x + "," + z + ")");
            }
        }
        for (Direction direction : Direction.VALUES) {
            assertArrayEquals(RoadSurfaceLayout.sectionProfile(RoadType.NONE),
                    RoadSurfaceLayout.edgeProfile(grid, direction),
                    "cap " + direction + " edge must be sealed with walk");
        }
    }

    // --------------------------------------------------------------- table -----

    @Test
    void tableCoversEveryReachableKeyAndDeclinesTheIsolatedCollector() {
        assertEquals(33, RoadPartTable.partIds().size(),
                "29 Citylines representatives + 4 local pieces");
        assertEquals(110, keys().size(),
                "15 collector-centre + 81 primary-centre + 14 local-centre keys");
        assertNull(RoadPartTable.entryFor(RoadType.SECONDARY, RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE),
                "an isolated collector cell has no asset by design");
        assertNull(RoadPartTable.entryFor(RoadType.TERTIARY, RoadType.NONE, RoadType.NONE,
                        RoadType.NONE, RoadType.NONE),
                "an isolated local cell has no asset by design (the planner never publishes one)");
        assertNotNull(RoadPartTable.entryFor(RoadType.PRIMARY, RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE));
        assertNull(RoadPartTable.entryFor(RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE));
    }

    // ------------------------------------------------------------- palettes ----

    @Test
    void paletteAndDamageChainMatchTheMaterialSpec() {
        Map<String, Object> street = document(ASSETS.resolve("palettes/street.json"));
        Map<Character, String> blocks = new LinkedHashMap<>();
        for (Object element : TestJson.array(street.get("palette"))) {
            Map<String, Object> entry = TestJson.object(element);
            blocks.put(((String) entry.get("char")).charAt(0), (String) entry.get("block"));
        }
        assertEquals(Map.of(
                'P', "minecraft:gray_concrete",
                'R', "minecraft:smooth_stone",
                'C', "minecraft:white_concrete",
                'F', "minecraft:light_gray_concrete",
                'K', "minecraft:polished_andesite",
                'b', "minecraft:structure_void"), blocks);

        Map<String, Object> damage = document(ASSETS.resolve("palettes/road_damage.json"));
        Map<String, String> damaged = new LinkedHashMap<>();
        for (Object element : TestJson.array(damage.get("palette"))) {
            Map<String, Object> entry = TestJson.object(element);
            damaged.put((String) entry.get("block"), (String) entry.get("damaged"));
        }
        assertEquals(Map.of(
                "minecraft:gray_concrete", "minecraft:andesite",
                "minecraft:smooth_stone", "minecraft:andesite",
                "minecraft:white_concrete", "minecraft:andesite",
                "minecraft:light_gray_concrete", "minecraft:andesite",
                "minecraft:polished_andesite", "minecraft:andesite"), damaged,
                "controlled damage must map every Citylines state to a full-height, non-gravity block");

        Map<String, Object> style = document(ASSETS.resolve("styles/standard.json"));
        List<Object> groups = TestJson.array(style.get("randompalettes"));
        List<Object> lastGroup = TestJson.array(groups.get(groups.size() - 1));
        assertEquals(1, lastGroup.size(), "the damage palette must be its own style group");
        assertEquals("citylines:road_damage", TestJson.object(lastGroup.get(0)).get("palette"),
                "the damage group must be merged last so its rules win");

        Map<String, Object> citystyle = document(ASSETS.resolve("citystyles/standard.json"));
        assertEquals("citylines:standard", citystyle.get("style"));
        assertEquals("lostcities:citystyle_common", citystyle.get("inherit"));

        Map<String, Object> worldstyle = document(ASSETS.resolve("worldstyles/standard.json"));
        List<Object> selectors = TestJson.array(worldstyle.get("citystyles"));
        assertEquals(1, selectors.size());
        assertEquals("citylines:standard", TestJson.object(selectors.get(0)).get("citystyle"));
    }

    // -------------------------------------------------------------- helpers ----

    /**
     * Every key this table can place, enumerated exactly as production does.
     *
     * <p>For a {@code TERTIARY} centre the logical arms are all {@code {NONE, TERTIARY}}, but an
     * entrance arm is rendered with the neighbour's section, so the reachable keys are the
     * promotions of those arms -- and a local cell is only ever degree 1 or 2 (measured
     * {@code {1: 385, 2: 8937}}), never isolated. Enumeration therefore covers the local through
     * roads (both ends alike) and the local ends, which is what {@link RoadPartTable} declares.
     */
    private static RoadType[] nnnn() {
        return new RoadType[] {RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE};
    }

    private static List<Key> keys() {
        List<Key> keys = new ArrayList<>();
        for (RoadType centre : List.of(RoadType.SECONDARY, RoadType.PRIMARY)) {
            for (RoadType n : RoadPartTable.classesFor(centre)) {
                for (RoadType e : RoadPartTable.classesFor(centre)) {
                    for (RoadType s : RoadPartTable.classesFor(centre)) {
                        for (RoadType w : RoadPartTable.classesFor(centre)) {
                            if (RoadPartTable.entryFor(centre, n, e, s, w) == null) {
                                continue;
                            }
                            keys.add(new Key(centre, n, e, s, w));
                        }
                    }
                }
            }
        }
        // Local centres: fourteen reachable port keys, including the four pure-local corners.
        for (Direction d : Direction.VALUES) {
            RoadType[] arms = nnnn();
            arms[d.ordinal()] = RoadType.TERTIARY;
            keys.add(new Key(RoadType.TERTIARY, arms[0], arms[1], arms[2], arms[3]));
        }
        for (int axis = 0; axis < 2; axis++) {
            RoadType[] arms = nnnn();
            arms[axis] = RoadType.TERTIARY;
            arms[axis + 2] = RoadType.TERTIARY;
            keys.add(new Key(RoadType.TERTIARY, arms[0], arms[1], arms[2], arms[3]));
        }
        for (int axis = 0; axis < 2; axis++) {
            for (int rot = 0; rot < 2; rot++) {
                RoadType[] arms = nnnn();
                arms[(axis + rot * 2) % 4] = RoadType.TERTIARY;
                arms[(axis + 2 + rot * 2) % 4] = RoadType.SECONDARY;
                keys.add(new Key(RoadType.TERTIARY, arms[0], arms[1], arms[2], arms[3]));
            }
        }
        for (Direction direction : Direction.VALUES) {
            RoadType[] arms = nnnn();
            arms[direction.ordinal()] = RoadType.TERTIARY;
            arms[direction.clockwise().ordinal()] = RoadType.TERTIARY;
            keys.add(new Key(RoadType.TERTIARY, arms[0], arms[1], arms[2], arms[3]));
        }
        return keys;
    }

    private static byte[] placed(Key key) {
        RoadPartTable.Entry entry = RoadPartTable.entryFor(key.centre(), key.n(), key.e(), key.s(), key.w());
        assertNotNull(entry, "no table entry for " + key.signature());
        byte[] canonical = parts().get(stem(entry.part()));
        assertNotNull(canonical, "no generated file for " + entry.part());
        return RoadSurfaceLayout.rotateCw(canonical, entry.quarterTurns());
    }

    private static Map<String, byte[]> loadParts() {
        assertTrue(Files.isDirectory(PARTS), "generated parts directory is missing: " + PARTS.toAbsolutePath());
        Map<String, byte[]> loaded = new LinkedHashMap<>();
        int decks = 0;
        try (Stream<Path> files = Files.list(PARTS)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name = stem(file.getFileName().toString());
                if (BRIDGE_DECK_FILE.equals(name)) {
                    // Not a street piece; BridgeDeckAssetsTest owns its contract. It must
                    // still exist exactly once (and the palette/chain tests read it).
                    assertEquals(1, ++decks, "exactly one bridge deck file expected: " + file);
                    continue;
                }
                loaded.put(name, gridOf(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(1, decks, "parts/ must contain exactly one " + BRIDGE_DECK_FILE + ".json");
        return loaded;
    }

    private static byte[] gridOf(Path file) {
        Map<String, Object> doc = document(file);
        assertEquals(16, TestJson.intValue(doc, "xsize"), file + ": xsize");
        assertEquals(16, TestJson.intValue(doc, "zsize"), file + ": zsize");
        assertEquals(RoadPartTable.PALETTE, doc.get("refpalette"), file + ": refpalette");
        List<Object> slices = TestJson.array(doc.get("slices"));
        assertEquals(1, slices.size(), file + ": exactly one slice");
        List<Object> rows = TestJson.array(slices.get(0));
        assertEquals(16, rows.size(), file + ": 16 rows");
        byte[] grid = new byte[RoadSurfaceLayout.SIZE * RoadSurfaceLayout.SIZE];
        for (int z = 0; z < RoadSurfaceLayout.SIZE; z++) {
            String row = (String) rows.get(z);
            assertEquals(16, row.length(), file + ": row " + z + " length");
            for (int x = 0; x < RoadSurfaceLayout.SIZE; x++) {
                int role = RoadSurfaceLayout.roleOf(row.charAt(x));
                assertTrue(role >= 0, file + ": unknown palette char '" + row.charAt(x) + "'");
                grid[z * RoadSurfaceLayout.SIZE + x] = (byte) role;
            }
        }
        return grid;
    }

    private static Map<String, Object> document(Path file) {
        assertTrue(Files.isRegularFile(file), "missing asset: " + file.toAbsolutePath());
        try {
            return TestJson.object(TestJson.read(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stem(String name) {
        String path = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        return path.endsWith(".json") ? path.substring(0, path.length() - 5) : path;
    }

    private static String roleString(byte[] profile) {
        StringBuilder sb = new StringBuilder(profile.length);
        for (byte role : profile) {
            sb.append(RoadSurfaceLayout.character(role));
        }
        return sb.toString();
    }

    private static String describe(byte[] actual, byte[] expected) {
        StringBuilder sb = new StringBuilder();
        for (int z = 0; z < RoadSurfaceLayout.SIZE; z++) {
            for (int x = 0; x < RoadSurfaceLayout.SIZE; x++) {
                byte a = RoadSurfaceLayout.role(actual, x, z);
                byte e = RoadSurfaceLayout.role(expected, x, z);
                sb.append(a == e ? RoadSurfaceLayout.character(a) : '*');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String text(RoadType type) {
        return switch (type) {
            case NONE -> "0";
            case TERTIARY -> "t";
            case SECONDARY -> "s";
            case PRIMARY -> "p";
        };
    }
}
