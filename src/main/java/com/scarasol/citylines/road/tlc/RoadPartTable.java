package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.RoadType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Maps centre/port classes to 33 canonical street pieces (110 directed keys). */
public final class RoadPartTable {

    /** Namespace of the generated parts. */
    private static final int KEY_BITS = 2;

    public static final String NAMESPACE = "citylines";

    /** Part-local palette every generated piece references. */
    public static final String PALETTE = "citylines:street";

    /**
     * One table entry.
     *
     * @param part          full part id, e.g. {@code citylines:road_p_0sp0}
     * @param quarterTurns  clockwise quarter turns to apply when placing the part
     */
    public record Entry(String part, int quarterTurns) {

        public Entry {
            if (part == null || part.isEmpty()) {
                throw new IllegalArgumentException("part id required");
            }
            if (quarterTurns < 0 || quarterTurns > 3) {
                throw new IllegalArgumentException("quarterTurns must be 0..3: " + quarterTurns);
            }
        }
    }


    private static final Map<Integer, Entry> BY_KEY = new LinkedHashMap<>();
    private static final List<Entry> ENTRIES;
    private static final List<String> PART_IDS;

    static {
        List<Entry> entries = new ArrayList<>();
        TreeSet<String> ids = new TreeSet<>();
        // Only the two centres with a purely class-based key space are enumerated here. A local
        // centre's keys depend on whether each arm is an entrance, which the class alphabet cannot
        // express, so its pieces are declared explicitly and resolved by entryForLocal.
        for (RoadType centre : new RoadType[] {RoadType.SECONDARY, RoadType.PRIMARY}) {
            for (RoadType edgeN : classesFor(centre)) {
                for (RoadType edgeE : classesFor(centre)) {
                    for (RoadType edgeS : classesFor(centre)) {
                        for (RoadType edgeW : classesFor(centre)) {
                            Entry entry = compute(centre, edgeN, edgeE, edgeS, edgeW);
                            if (entry == null) {
                                continue;
                            }
                            BY_KEY.put(selectionKey(centre, edgeN, edgeE, edgeS, edgeW), entry);
                            entries.add(entry);
                            ids.add(entry.part());
                        }
                    }
                }
            }
        }
        // The local family, registered by its resolved geometric ports. Four canonical
        // pieces cover the fourteen reachable local port keys; the four single-arm directions share
        // road_t_000t through the renderer's rotation, exactly as the Citylines family does.
        registerLocal(ids, RoadType.NONE, RoadType.TERTIARY, RoadType.NONE, RoadType.TERTIARY);
        registerLocal(ids, RoadType.NONE, RoadType.TERTIARY, RoadType.NONE, RoadType.SECONDARY);
        registerLocal(ids, RoadType.TERTIARY, RoadType.NONE, RoadType.NONE, RoadType.NONE);
        registerLocal(ids, RoadType.NONE, RoadType.NONE, RoadType.TERTIARY, RoadType.TERTIARY);

        ENTRIES = List.copyOf(entries);
        PART_IDS = List.copyOf(ids);
    }

    /**
     * Registers one canonical local shape plus every rotation of it.
     *
     * <p>The rotations are what turn four assets into fourteen keys: a local cell's arm can point any
     * way, and the renderer rotates the piece. Registering all four rotations here is what makes
     * {@link #entryForLocal} succeed for every direction, while {@link #partIds()} still lists
     * only the four real files.
     */
    private static void registerLocal(TreeSet<String> ids, RoadType edgeN, RoadType edgeE,
                                      RoadType edgeS, RoadType edgeW) {
        RoadType[] arms = {edgeN, edgeE, edgeS, edgeW};
        Entry canonical = null;
        int canonicalTurns = 0;
        for (int k = 0; k < Direction.COUNT; k++) {
            RoadType[] rotated = new RoadType[Direction.COUNT];
            for (int i = 0; i < Direction.COUNT; i++) {
                rotated[i] = arms[(i + k) % Direction.COUNT];
            }
            Entry entry = computeLocal(rotated[0], rotated[1], rotated[2], rotated[3]);
            if (entry == null) {
                continue;
            }
            int key = localPortKey(0, rotated);
            BY_KEY.put(key, entry);
            if (canonical == null) {
                canonical = entry;
                canonicalTurns = entry.quarterTurns();
            }
        }
        if (canonical != null) {
            ids.add(canonical.part());
        }
    }


    private RoadPartTable() {
    }

    /**
     * The <b>point class</b> alphabet of a centre: the classes this table has assets for.
     *
     * <p>A shared edge is {@code min(centre, neighbour)} logically, so one of these
     * {@code NONE}, {@code SECONDARY}, {@code TERTIARY} or {@code PRIMARY} keys is chosen and
     * then resolved. Note the V3 geometry alias lives in the graph layer, <b>not</b> here: a
     * local-road arm at a higher centre is mapped to the medium port before the lookup, so
     * that this table keeps exactly the seven tens it always had plus the narrow local
     * family. A {@code TERTIARY} centre only ever carries {@code {NONE, TERTIARY}} ports.
     */
    public static List<RoadType> classesFor(RoadType centre) {
        return centre == RoadType.PRIMARY
                ? List.of(RoadType.NONE, RoadType.SECONDARY, RoadType.PRIMARY)
                : List.of(RoadType.NONE, RoadType.SECONDARY);
    }


    /**
     * The <b>geometric</b> port class for one arm: a {@code TERTIARY} arm at a centre above
     * {@code TERTIARY} is rendered with the medium port, because the narrow section starts at
     * the first local cell, not inside the higher-class chunk.
     *
     * <p>This is a rendering alias only. It must never be written back into the logical edge
     * class, the road rights or any topology statistic — the knowledge base is explicit about
     * that, and V3's {@code transitionMask} exists precisely so the geometry can differ from
     * the logical level without changing it.
     */
    public static RoadType portClass(RoadType centre, RoadType logicalEdge) {
        if (logicalEdge == null || !logicalEdge.isRoad()) {
            // A no-edge arm must stay NONE. Aliasing it to the medium port would punch carriageway
            // through a side that has no connection.
            return RoadType.NONE;
        }
        if (centre == RoadType.TERTIARY) {
            // The centre's own class. The *entrance* arm is decided by transitionMask, not here,
            // because the logical edge class alone cannot tell an entrance arm from a local one.
            return RoadType.TERTIARY;
        }
        if (logicalEdge == RoadType.TERTIARY) {
            // A local arm at a higher-class centre presents the medium port.
            return RoadType.SECONDARY;
        }
        return RoadType.min(logicalEdge, centre);
    }

    /**
     * The arm class a <b>local</b> centre presents, given the plan's per-arm transition bit.
     *
     * <p>A transition bit means "this arm leaves into a primary/secondary road", so it presents
     * the medium entrance section; otherwise the arm is purely local and stays narrow. A no-edge
     * arm is NONE regardless of the bit.
     */
    public static RoadType localPort(RoadType logicalEdge, boolean transitionArm) {
        if (logicalEdge == null || !logicalEdge.isRoad()) {
            return RoadType.NONE;
        }
        return transitionArm ? RoadType.SECONDARY : RoadType.TERTIARY;
    }

    /**
     * Table entry for an explicit key, or {@code null} when there is none.
     *
     * <p>Edge classes above the centre are impossible from the planner; they are
     * clamped to the centre so a malformed key degrades to the narrower port instead
     * of asking for a piece whose port is wider than the chunk's own class.
     */
    public static Entry entryFor(RoadType centre, RoadType edgeN, RoadType edgeE, RoadType edgeS, RoadType edgeW) {
        if (centre == null || !centre.isRoad() || edgeN == null || edgeE == null || edgeS == null || edgeW == null) {
            return null;
        }
        if (centre == RoadType.TERTIARY) {
            // A caller that already resolved the entrance arm passes it as SECONDARY; recover the
            // transition bits from the arms themselves so this entry point works for both the
            // logical form (all TERTIARY) and the resolved form. Callers holding the plan should
            // prefer entryForLocal(...) with the plan's own mask.
            int mask = 0;
            RoadType[] arms = {edgeN, edgeE, edgeS, edgeW};
            for (Direction direction : Direction.VALUES) {
                if (arms[direction.ordinal()] == RoadType.SECONDARY) {
                    mask |= direction.bit();
                }
            }
            return entryForLocal(edgeN, edgeE, edgeS, edgeW, mask);
        }
        // Clamp every arm to the centre: an arm can never be stronger than its own chunk, so a
        // malformed key degrades to the narrower port instead of asking for a piece that does not
        // exist. The clamped key must exist; only the two isolated non-primary cells are absent.
        return center(centre, portClass(centre, edgeN), portClass(centre, edgeE),
                portClass(centre, edgeS), portClass(centre, edgeW));
    }

    /**
     * Lookup for a <b>local</b> centre ({@code TERTIARY}).
     *
     * <p>Four reachable shapes, and the plan's {@code transitionMask} says which arm is the
     * entrance — the logical edge class cannot, because {@code min} makes an entrance arm and a
     * local arm look identical. Measured over 10 seeds x 7x7 supercells, every local cell is one
     * of: a single-arm end, a pure-local through road, an entrance arm plus a local arm, or a
     * pure-local corner.
     * {@code 0s0s}, {@code 000s} and {@code 000p} are not reachable and deliberately have no
     * asset.
     *
     * @param transitionMask the plan's per-arm entrance bits, or 0 when the caller has none (in
     *                       which case only the pure-local shapes can be resolved)
     */
    public static Entry entryForLocal(RoadType edgeN, RoadType edgeE, RoadType edgeS, RoadType edgeW,
                                      int transitionMask) {
        RoadType[] logical = {edgeN, edgeE, edgeS, edgeW};
        RoadType[] ports = new RoadType[Direction.COUNT];
        int degree = 0;
        for (Direction direction : Direction.VALUES) {
            RoadType edge = logical[direction.ordinal()];
            if (edge != null && edge.rank() > RoadType.SECONDARY.rank()) {
                // The logical arm of a local cell is capped at TERTIARY by min(centre, neighbour);
                // the only arm allowed to exceed that is the resolved entrance, which is
                // SECONDARY. A PRIMARY arm on a local cell is malformed, not a shape to answer.
                return null;
            }
            boolean hasEdge = edge != null && edge.isRoad();
            if (hasEdge) {
                degree++;
            }
            // The transition bit is only meaningful on an arm that exists.
            boolean entrance = hasEdge && (transitionMask & direction.bit()) != 0;
            ports[direction.ordinal()] = localPort(edge, entrance);
        }
        if (degree < 1 || degree > 2) {
            // Never isolated, never a junction: the design forbids local junctions and the planner
            // never publishes an isolated local cell (measured degree histogram {1: 385, 2: 8937}).
            return null;
        }
        return BY_KEY.get(localPortKey(transitionMask, ports));
    }

    private static Entry center(RoadType centre, RoadType edgeN, RoadType edgeE, RoadType edgeS, RoadType edgeW) {
        return BY_KEY.get(selectionKey(centre, edgeN, edgeE, edgeS, edgeW));
    }

    /** Resolve a local shape against a supplied table (production passes the real one). */
    private static Entry localLookup(Map<Integer, Entry> table, RoadType[] logical, int transitionMask) {
        RoadType[] ports = new RoadType[Direction.COUNT];
        int degree = 0;
        for (Direction direction : Direction.VALUES) {
            RoadType edge = logical[direction.ordinal()];
            if (edge == null || !edge.isRoad()) {
                ports[direction.ordinal()] = RoadType.NONE;
                continue;
            }
            degree++;
            ports[direction.ordinal()] = localPort(edge, (transitionMask & direction.bit()) != 0);
        }
        if (degree < 1 || degree > 2) {
            return null;
        }
        return table.get(localPortKey(transitionMask, ports));
    }

    /**
     * Packed key of a <b>local</b> centre from its resolved geometric ports.
     *
     * <p>Kept separate from the logical edge key because the two describe different
     * things: that one is the logical, class-only key shared with the plan (and with every Citylines
     * consumer), while this one includes the entrance resolution. Mixing them would make an
     * entrance arm look like a plain medium arm to callers that must keep seeing the logical
     * class.
     */
    public static int localPortKey(int transitionMask, RoadType... ports) {
        int key = RoadType.TERTIARY.ordinal();
        for (Direction direction : Direction.VALUES) {
            key |= ports[direction.ordinal()].ordinal()
                    << (KEY_BITS * (direction.ordinal() + 1));
        }
        // The transition bits are already implied by which arms are SECONDARY, so they are not
        // packed: packing them too would create two keys per shape.
        return key;
    }

    /**
     * Every <b>reachable</b> key that must resolve, as a fail-closed gate.
     *
     * <p>Checking that the part <em>files</em> exist is not enough: a key with no table entry is a
     * chunk the renderer cannot pave, and a missing part is a hard crash in Lost Cities
     * ({@code RegistryAssetRegistry.get} rethrows). So this enumerates the keys the planners can
     * actually publish and reports any that do not resolve.
     *
     * <p>The families, each derived from what the planners can emit rather than from the raw
     * alphabets:
     * <ul>
     *   <li>Citylines collector centre: {@code {NONE, SECONDARY}} arms, minus the isolated {@code 0000}
     *       (the planner never publishes an isolated collector);</li>
     *   <li>Citylines arterial centre: {@code {NONE, SECONDARY, PRIMARY}} arms including the cap;</li>
     *   <li>V3 local centre: fourteen port keys (four single-arm ends, two pure-local throughs,
     *       four entrance+local, four pure-local corners), which is where {@code transitionMask}
     *       matters.</li>
     * </ul>
     *
     * @return the unresolved keys, empty when every reachable key is covered
     */
    public static List<String> unresolvedReachableKeys() {
        return unresolvedReachableKeys(BY_KEY);
    }

    /**
     * Testable form of {@link #unresolvedReachableKeys()}: the same enumeration against a supplied
     * key table, so a negative case can prove the gate actually reports a gap.
     */
    static List<String> unresolvedReachableKeys(Map<Integer, Entry> table) {
        List<String> unresolved = new ArrayList<>();
        for (RoadType centre : new RoadType[] {RoadType.SECONDARY, RoadType.PRIMARY}) {
            List<RoadType> alphabet = classesFor(centre);
            for (RoadType n : alphabet) {
                for (RoadType e : alphabet) {
                    for (RoadType s : alphabet) {
                        for (RoadType w : alphabet) {
                            if (centre == RoadType.SECONDARY && !n.isRoad() && !e.isRoad()
                                    && !s.isRoad() && !w.isRoad()) {
                                continue;
                            }
                            if (table.get(selectionKey(centre, n, e, s, w)) == null) {
                                unresolved.add(centre + " " + letters(new RoadType[] {n, e, s, w}));
                            }
                        }
                    }
                }
            }
        }
        // The local family: four ends, two pure-local throughs, four entrance+local shapes,
        // and four pure-local corners.
        for (Direction direction : Direction.VALUES) {
            RoadType[] arms = {RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE};
            arms[direction.ordinal()] = RoadType.TERTIARY;
            if (localLookup(table, arms, 0) == null) {
                unresolved.add("TERTIARY end " + direction);
            }
        }
        for (int axis = 0; axis < 2; axis++) {
            RoadType[] arms = {RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE};
            arms[axis] = RoadType.TERTIARY;
            arms[axis + 2] = RoadType.TERTIARY;
            if (localLookup(table, arms, 0) == null) {
                unresolved.add("TERTIARY through axis" + axis);
            }
        }
        for (int axis = 0; axis < 2; axis++) {
            for (int rot = 0; rot < 2; rot++) {
                RoadType[] arms = {RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE};
                Direction local = Direction.VALUES[(axis + rot * 2) % 4];
                Direction entrance = Direction.VALUES[(axis + 2 + rot * 2) % 4];
                arms[local.ordinal()] = RoadType.TERTIARY;
                arms[entrance.ordinal()] = RoadType.TERTIARY;
                if (localLookup(table, arms, entrance.bit()) == null) {
                    unresolved.add("TERTIARY entrance " + entrance + " + local " + local);
                }
            }
        }
        for (Direction direction : Direction.VALUES) {
            RoadType[] arms = {RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE};
            arms[direction.ordinal()] = RoadType.TERTIARY;
            arms[direction.clockwise().ordinal()] = RoadType.TERTIARY;
            if (localLookup(table, arms, 0) == null) {
                unresolved.add("TERTIARY corner " + direction);
            }
        }
        return unresolved;
    }

    /**
     * A copy of the real key table, keyed by packed key.
     *
     * <p>For tests that need to mutate the table (a negative case for the coverage gate); the copy
     * keeps the shared instance immutable.
     */
    static Map<Integer, Entry> keyTable() {
        return new LinkedHashMap<>(BY_KEY);
    }

    /** Every generated part id; used by the dimension pre-check. */
    public static List<String> partIds() {
        return PART_IDS;
    }

    /** Every directed entry, in a stable order. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * Packed key, identical to the logical edge key — the two must change
     * together or the table silently stops matching the planner.
     *
     * <p>Fields are {@value #KEY_BITS} bits of {@link RoadType#ordinal()}:
     * {@code [centre][N][E][S][W]}.
     */
    public static int selectionKey(RoadType centre, RoadType edgeN, RoadType edgeE, RoadType edgeS, RoadType edgeW) {
        int key = centre.ordinal();
        RoadType[] edges = {edgeN, edgeE, edgeS, edgeW};
        for (Direction direction : Direction.VALUES) {
            key |= RoadType.min(edges[direction.ordinal()], centre).ordinal()
                    << (KEY_BITS * (direction.ordinal() + 1));
        }
        return key;
    }

    /**
     * Canonical piece of a shape given as <b>already resolved geometric ports</b>, or {@code null}
     * when no asset covers it.
     *
     * <p>Deliberately free of alias logic: the caller decides the ports (that is where the V3
     * entrance resolution lives) and this method only canonicalises and names. Keeping the alias
     * out of here is what stops an entrance arm from being silently re-classed a second time.
     */
    private static Entry compute(RoadType centre, RoadType edgeN, RoadType edgeE, RoadType edgeS, RoadType edgeW) {
        if (!centre.isRoad()) {
            return null;
        }
        RoadType[] edges = {
                edgeN == null ? RoadType.NONE : RoadType.min(edgeN, centre),
                edgeE == null ? RoadType.NONE : RoadType.min(edgeE, centre),
                edgeS == null ? RoadType.NONE : RoadType.min(edgeS, centre),
                edgeW == null ? RoadType.NONE : RoadType.min(edgeW, centre),
        };
        if (centre.rank() < RoadType.PRIMARY.rank() && !edges[0].isRoad() && !edges[1].isRoad()
                && !edges[2].isRoad() && !edges[3].isRoad()) {
            // Isolated collector or local cell: no asset by design, because the planner never
            // publishes one and every port would be all walk. Measured for TERTIARY over
            // 6 seeds x 7x7 supercells: degree histogram {1: 385, 2: 8937} -- never 0.
            return null;
        }
        RoadType[] best = null;
        int bestTurns = 0;
        // canonical = counter-clockwise rotation of the key, i.e. the piece that needs
        // `k` clockwise turns to produce the key (Transform.ROTATE_90 maps W to N).
        for (int k = 0; k < Direction.COUNT; k++) {
            RoadType[] candidate = new RoadType[Direction.COUNT];
            for (int i = 0; i < Direction.COUNT; i++) {
                candidate[i] = edges[(i + k) % Direction.COUNT];
            }
            if (best == null || less(candidate, best)) {
                best = candidate;
                bestTurns = k;
            }
        }
        return new Entry(NAMESPACE + ":road_" + letter(centre) + "_" + letters(best), bestTurns);
    }

    /**
     * Canonical local piece from <b>already resolved</b> geometric ports.
     *
     * <p>Unlike {@link #compute} this does <b>not</b> clamp arms to the centre: an entrance arm is
     * deliberately wider than the local centre (that is the whole point of the entrance transition
     * piece), so clamping would collapse {@code 0t0s} into {@code 0t0t} and silently render an
     * entrance with the local section.
     */
    private static Entry computeLocal(RoadType edgeN, RoadType edgeE, RoadType edgeS, RoadType edgeW) {
        RoadType[] ports = {edgeN, edgeE, edgeS, edgeW};
        int degree = 0;
        for (RoadType port : ports) {
            if (port.isRoad()) {
                degree++;
            }
        }
        if (degree < 1 || degree > 2) {
            // Never isolated, never a junction.
            return null;
        }
        RoadType[] best = null;
        int bestTurns = 0;
        for (int k = 0; k < Direction.COUNT; k++) {
            RoadType[] candidate = new RoadType[Direction.COUNT];
            for (int i = 0; i < Direction.COUNT; i++) {
                candidate[i] = ports[(i + k) % Direction.COUNT];
            }
            if (best == null || less(candidate, best)) {
                best = candidate;
                bestTurns = k;
            }
        }
        return new Entry(NAMESPACE + ":road_t_" + letters(best), bestTurns);
    }

    /** Canonical comparison uses the same hierarchy order as the asset generator. */
    private static boolean less(RoadType[] a, RoadType[] b) {
        for (int i = 0; i < a.length; i++) {
            int rankA = a[i].rank();
            int rankB = b[i].rank();
            if (rankA != rankB) {
                return rankA < rankB;
            }
        }
        return false;
    }

    /**
     * Centre-class letter of a generated part file: {@code p}, {@code s} or {@code t}.
     *
     * <p>Must agree with the generator's own naming ({@code tools/generate_road_parts.py})
     * and with the four edge letters below.
     */
    private static char letter(RoadType centre) {
        return switch (centre) {
            case PRIMARY -> 'p';
            case SECONDARY -> 's';
            case TERTIARY -> 't';
            case NONE -> throw new IllegalArgumentException("NONE has no part");
        };
    }

    /** Four edge letters in {@code N/E/S/W} order: {@code 0}, {@code t}, {@code s}, {@code p}. */
    private static String letters(RoadType[] edges) {
        StringBuilder sb = new StringBuilder(Direction.COUNT);
        for (RoadType edge : edges) {
            sb.append(switch (edge) {
                case NONE -> '0';
                case TERTIARY -> 't';
                case SECONDARY -> 's';
                case PRIMARY -> 'p';
            });
        }
        return sb.toString();
    }
}
