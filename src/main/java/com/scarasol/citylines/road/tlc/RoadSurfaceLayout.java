package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.RoadSection;
import com.scarasol.citylines.road.core.RoadType;

/**
 * Role grid of one 16x16 road piece.
 *
 * <p>This is the executable form of the cross-sections ({@link RoadSection}) and of the
 * {@code (centre class, four shared edge classes)} piece contract:
 *
 * <ol>
 *   <li>every connected edge adds one <b>arm</b>: the shared edge class's band along the
 *       perpendicular axis, running from that edge to the far side of the centre square. The arm
 *       is painted in <em>that edge class's</em> lane material, so a collector arm of an arterial
 *       piece matches the collector piece beside it cell for cell;</li>
 *   <li>the <b>centre square</b> — the piece's own class band on both axes — is painted last, so a
 *       narrower arm can never repaint the junction surface;</li>
 *   <li>the <b>centre line</b> (decision D4) exists on the arterial tier only. Every arterial arm
 *       carries it — that is what keeps a shared arterial edge identical on both sides — and it
 *       also runs through the centre square only when no perpendicular arm enters it.
 *       Junction squares stay plain, the way a real junction does;</li>
 *   <li><b>kerb</b> — every non-paved cell orthogonally adjacent to a paved cell, so a dead end is
 *       closed by kerb + walk inside the piece;</li>
 *   <li><b>walk</b> — every remaining cell. The union of the cross-sections covers the whole
 *       16x16 piece, so no cell is {@link #SKIP} today ({@code b} = {@code structure_void} remains
 *       the designated skip character).</li>
 * </ol>
 *
 * <p>Consequences that the asset contract test relies on:
 * <ul>
 *   <li>the role string on an edge is a pure function of <b>that edge's</b> class
 *       ({@link #sectionProfile});</li>
 *   <li>a {@link RoadType#NONE} edge never contains a paved cell.</li>
 * </ul>
 *
 * <p>Pure code: no Minecraft types, so the asset test can run it without a game.
 * The generated JSON in {@code src/main/resources/data/citylines/lostcities/parts}
 * is produced by {@code tools/generate_road_parts.py}, whose geometry mirrors
 * this class cell for cell.
 */
public final class RoadSurfaceLayout {

    /** Piece edge length. */
    public static final int SIZE = 16;

    /** Cell that is deliberately not written ({@code b} = {@code structure_void}). */
    public static final byte SKIP = 0;
    public static final byte WALK = 1;
    public static final byte KERB = 2;
    /** Arterial and collector carriageway: concrete. */
    public static final byte CARRIAGEWAY = 3;
    /** Local carriageway: light stone, so the narrowest tier differs in material as well. */
    public static final byte LOCAL_CARRIAGEWAY = 4;
    /** Arterial centre line: the two middle cells of a 12-wide carriageway. */
    public static final byte CENTRE = 5;

    private RoadSurfaceLayout() {
    }

    /**
     * Walk-band width of a class, in blocks.
     *
     * <p>Read from {@link RoadSection} rather than repeated here: the two used to hold
     * separate copies of the numbers, which is exactly how a third class ends up with a
     * layout that disagrees with the generated asset.
     */
    public static int walk(RoadType type) {
        return type.isRoad() ? RoadSection.of(type).walk() : 0;
    }

    /** Kerb width of a class; the frozen sections always use one flat block. */
    public static int kerb(RoadType type) {
        return type.isRoad() ? RoadSection.of(type).kerb() : 0;
    }

    /** Carriageway width of a class, in blocks. */
    public static int carriageway(RoadType type) {
        return type.isRoad() ? RoadSection.of(type).carriageway() : 0;
    }

    /** First cell (inclusive) of a class's centred carriageway band. */
    public static int bandLo(RoadType type) {
        return walk(type) + kerb(type);
    }

    /** One past the last cell of a class's centred carriageway band. */
    public static int bandHi(RoadType type) {
        return bandLo(type) + carriageway(type);
    }

    /** The driving-surface role of a class. */
    public static byte lane(RoadType type) {
        return type == RoadType.TERTIARY ? LOCAL_CARRIAGEWAY : CARRIAGEWAY;
    }

    /** True for every role that is driving surface, including the centre line. */
    public static boolean isPaved(byte role) {
        return role == CARRIAGEWAY || role == LOCAL_CARRIAGEWAY || role == CENTRE;
    }

    /** First cell of the arterial centre line; the line is always two cells wide. */
    public static int centreFrom() {
        return RoadSection.PRIMARY.centreFrom();
    }

    /** Whether a class paints a centre line at all. */
    public static boolean hasCentreLine(RoadType type) {
        return type.isRoad() && RoadSection.of(type).hasCentreLine();
    }

    /**
     * Role grid of a piece, indexed {@code grid[z * SIZE + x]} with {@code x} growing
     * east and {@code z} growing south (the same order as the part JSON rows).
     *
     * <p>For the class-based centres ({@code SECONDARY}, {@code PRIMARY}) an edge class above the
     * centre is impossible from the planner ({@code edgeClass = min(own, neighbour)}), so it is
     * clamped to the centre and a malformed key can never produce a port wider than the piece.
     *
     * <p>A <b>local</b> ({@code TERTIARY}) centre is the one exception, and it is not malformed:
     * its entrance arm is deliberately wider than the centre, because the entrance transition
     * piece carries the neighbour's medium section on that arm and narrows to the local section on
     * its purely local arms. Clamping here would silently render the entrance at the local width,
     * which is exactly the defect this path exists to avoid. Callers therefore pass a local
     * centre's arms <b>already resolved</b> to geometric ports.
     */
    public static byte[] grid(RoadType centre, RoadType edgeN, RoadType edgeE, RoadType edgeS, RoadType edgeW) {
        if (!centre.isRoad()) {
            throw new IllegalArgumentException("a piece needs a road centre class");
        }
        boolean localCentre = centre == RoadType.TERTIARY;
        RoadType[] edges = {edgeN, edgeE, edgeS, edgeW};
        byte[] grid = new byte[SIZE * SIZE]; // SKIP
        int lo = bandLo(centre);
        int hi = bandHi(centre);

        for (Direction direction : Direction.VALUES) {
            RoadType cls = portClass(edges[direction.ordinal()], centre, localCentre);
            if (cls == null || !cls.isRoad()) {
                continue;
            }
            int aLo = bandLo(cls);
            int aHi = bandHi(cls);
            byte lane = lane(cls);
            switch (direction) {
                case N -> fill(grid, aLo, 0, aHi - 1, hi - 1, lane);
                case S -> fill(grid, aLo, lo, aHi - 1, SIZE - 1, lane);
                case W -> fill(grid, 0, aLo, hi - 1, aHi - 1, lane);
                case E -> fill(grid, lo, aLo, SIZE - 1, aHi - 1, lane);
            }
        }
        // The junction surface wins over every arm: it is the piece's own class.
        fill(grid, lo, lo, hi - 1, hi - 1, lane(centre));
        paintCentreLine(grid, centre, edges, localCentre, lo, hi);

        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int index = z * SIZE + x;
                if (isPaved(grid[index])) {
                    continue;
                }
                if (isPaved(grid, x + 1, z) || isPaved(grid, x - 1, z)
                        || isPaved(grid, x, z + 1) || isPaved(grid, x, z - 1)) {
                    grid[index] = KERB;
                }
            }
        }
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] == SKIP) {
                grid[i] = WALK;
            }
        }
        return grid;
    }

    /**
     * Paints the arterial centre line: the two middle cells of every arterial arm, plus the piece
     * centre only when the primary road runs straight without a perpendicular entrance.
     *
     * <p>The arm part is what keeps a shared arterial edge identical from both sides, so it is
     * painted for <em>every</em> arterial port, including the arm of a local entrance piece. The
     * centre square is skipped at every junction, leaving only the lead-ins and preserving the
     * section of each shared chunk edge.
     */
    private static void paintCentreLine(byte[] grid, RoadType centre, RoadType[] edges,
                                        boolean localCentre, int lo, int hi) {
        boolean nLine = arterial(edges[0], centre, localCentre);
        boolean eLine = arterial(edges[1], centre, localCentre);
        boolean sLine = arterial(edges[2], centre, localCentre);
        boolean wLine = arterial(edges[3], centre, localCentre);
        int from = centreFrom();
        int to = from + 1;
        if (nLine) {
            fill(grid, from, 0, to, lo - 1, CENTRE);
        }
        if (sLine) {
            fill(grid, from, hi, to, SIZE - 1, CENTRE);
        }
        if (wLine) {
            fill(grid, 0, from, lo - 1, to, CENTRE);
        }
        if (eLine) {
            fill(grid, hi, from, SIZE - 1, to, CENTRE);
        }
        boolean throughZ = nLine && sLine;
        boolean throughX = eLine && wLine;
        boolean junction = (throughZ && (connected(edges[1]) || connected(edges[3])))
                || (throughX && (connected(edges[0]) || connected(edges[2])));
        if (!hasCentreLine(centre) || throughZ == throughX || junction) {
            return;
        }
        if (throughZ) {
            fill(grid, from, lo, to, hi - 1, CENTRE);
        } else {
            fill(grid, lo, from, hi - 1, to, CENTRE);
        }
    }

    /** The class an arm presents after clamping, or {@code null} when there is no arm. */
    private static RoadType portClass(RoadType raw, RoadType centre, boolean localCentre) {
        return localCentre ? raw : RoadType.min(raw, centre);
    }

    /** True when this edge is an arterial arm that must carry the centre line. */
    private static boolean arterial(RoadType raw, RoadType centre, boolean localCentre) {
        return portClass(raw, centre, localCentre) == RoadType.PRIMARY;
    }

    private static boolean connected(RoadType edge) {
        return edge != null && edge.isRoad();
    }

    /** Role grid for edge classes in {@link Direction} order N,E,S,W. */
    public static byte[] grid(RoadType centre, RoadType[] edges) {
        if (edges.length != Direction.COUNT) {
            throw new IllegalArgumentException("four edge classes expected");
        }
        return grid(centre, edges[0], edges[1], edges[2], edges[3]);
    }

    /**
     * Rotate a role grid clockwise by {@code turns} quarter turns, exactly like
     * {@code Transform.ROTATE_90} does at placement time: the role at part cell
     * {@code (x, z)} ends up at chunk cell {@code (15 - z, x)}.
     */
    public static byte[] rotateCw(byte[] grid, int turns) {
        byte[] result = grid.clone();
        for (int turn = 0; turn < ((turns % 4) + 4) % 4; turn++) {
            byte[] next = new byte[SIZE * SIZE];
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    next[x * SIZE + (SIZE - 1 - z)] = result[z * SIZE + x];
                }
            }
            result = next;
        }
        return result;
    }

    public static byte role(byte[] grid, int x, int z) {
        return grid[z * SIZE + x];
    }

    /** Roles along one edge, read left to right in that edge's own axis. */
    public static byte[] edgeProfile(byte[] grid, Direction direction) {
        byte[] profile = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) {
            profile[i] = switch (direction) {
                case N -> grid[i];
                case S -> grid[(SIZE - 1) * SIZE + i];
                case W -> grid[i * SIZE];
                case E -> grid[i * SIZE + SIZE - 1];
            };
        }
        return profile;
    }

    /**
     * The only edge role string a shared edge of class {@code edgeClass} may present:
     * all walk for {@link RoadType#NONE}, otherwise the class's section - including the two centre
     * cells for an arterial edge, because every arterial arm paints them.
     */
    public static byte[] sectionProfile(RoadType edgeClass) {
        byte[] profile = new byte[SIZE];
        if (!edgeClass.isRoad()) {
            java.util.Arrays.fill(profile, WALK);
            return profile;
        }
        int walk = walk(edgeClass);
        int kerb = kerb(edgeClass);
        int carriageway = carriageway(edgeClass);
        byte lane = lane(edgeClass);
        boolean centreLine = hasCentreLine(edgeClass);
        int from = carriageway > 0 ? RoadSection.of(edgeClass).centreFrom() : -1;
        int i = 0;
        for (int n = 0; n < walk; n++) {
            profile[i++] = WALK;
        }
        for (int n = 0; n < kerb; n++) {
            profile[i++] = KERB;
        }
        for (int n = 0; n < carriageway; n++) {
            int cell = walk + kerb + n;
            profile[i++] = centreLine && (cell == from || cell == from + 1) ? CENTRE : lane;
        }
        for (int n = 0; n < kerb; n++) {
            profile[i++] = KERB;
        }
        while (i < SIZE) {
            profile[i++] = WALK;
        }
        return profile;
    }

    /** Palette character of a role, matching {@code palettes/street.json}. */
    public static char character(byte role) {
        return switch (role) {
            case CARRIAGEWAY -> 'P';
            case LOCAL_CARRIAGEWAY -> 'R';
            case CENTRE -> 'C';
            case WALK -> 'F';
            case KERB -> 'K';
            default -> 'b';
        };
    }

    /** Role of a palette character; {@link #SKIP} for the skip character and -1 for unknown input. */
    public static int roleOf(char c) {
        return switch (c) {
            case 'P' -> CARRIAGEWAY;
            case 'R' -> LOCAL_CARRIAGEWAY;
            case 'C' -> CENTRE;
            case 'F' -> WALK;
            case 'K' -> KERB;
            case 'b', ' ' -> SKIP;
            default -> -1;
        };
    }

    private static boolean isPaved(byte[] grid, int x, int z) {
        return x >= 0 && z >= 0 && x < SIZE && z < SIZE && isPaved(grid[z * SIZE + x]);
    }

    private static void fill(byte[] grid, int x0, int z0, int x1, int z1, byte role) {
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
                    continue;
                }
                grid[z * SIZE + x] = role;
            }
        }
    }
}
