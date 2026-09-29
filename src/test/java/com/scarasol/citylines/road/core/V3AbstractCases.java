package com.scarasol.citylines.road.core;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Abstract-map probe for the pure V3 planner; needs no Forge and no world seed.
 *
 * <p>The invariants live in package-private {@code verify*} methods that throw
 * {@link AssertionError} with the offending coordinate and fact source, so the same
 * checks back both the CLI and {@code V3AbstractCasesTest}. {@code main} is a thin
 * command dispatcher over them (the original evidence command was
 * {@code V3AbstractCases verify}); {@code extreme}, {@code rare} and {@code sweep}
 * remain descriptive metric dumps.
 */
public final class V3AbstractCases {
    private static final List<V3Footprint> FOOTPRINTS = List.of(
            new V3Footprint(3, 3), new V3Footprint(2, 2), new V3Footprint(2, 1));
    private static final List<V3Footprint> LARGE_FOOTPRINTS = List.of(
            new V3Footprint(9, 9), new V3Footprint(7, 7),
            new V3Footprint(3, 3), new V3Footprint(2, 2), new V3Footprint(2, 1));

    private V3AbstractCases() { }

    /** The ordinary asset list the abstract evidence was measured with. */
    static List<V3Footprint> footprints() {
        return FOOTPRINTS;
    }

    /** The same list plus the 7x7/9x9 shapes that trigger the conditional fallback. */
    static List<V3Footprint> largeFootprints() {
        return LARGE_FOOTPRINTS;
    }

    public static void main(String[] args) {
        String mode = args.length == 0 ? "sweep" : args[0];
        switch (mode) {
            case "verify" -> {
                verify();
                System.out.println("V3 graph invariants passed");
            }
            case "extreme" -> extreme();
            case "grid" -> grid(false);
            case "grid-single" -> grid(true);
            case "rare" -> rareCompare();
            case "sweep" -> sweep();
            default -> throw new IllegalArgumentException("unknown V3AbstractCases mode: " + mode);
        }
    }

    /** Runs every abstract invariant; the CLI form of {@code V3AbstractCasesTest}. */
    static void verify() {
        verifyParameters();
        verifyStationChoices(V3Params.selectedForDefaultArea());
        verifyGapReasons(V3Params.selectedForDefaultArea());
        verifyTransitionPorts(V3Params.selectedForDefaultArea());
        verifyWorldInvariants();
        verifyOpenMapContent();
        verifyFeasibleParcels();
        verifyRareRescue();
        verifyContinuousLocals();
        verifyNonNestedRareFootprints(V3Params.selectedForDefaultArea());
        verifyLargeOnlyFootprint();
    }

    private static void rareCompare() {
        V3Params p = V3Params.selectedForDefaultArea();
        for (int period : new int[] {0, 2, 1}) {
            int localCells = 0;
            int retainedUnits = 0;
            int eligibleUnits = 0;
            for (long seed : new long[] {0, 1, 31, 101}) {
                V3FactSource city = (x, z) -> V3Facts.city(0);
                V3Planner ordinary = new V3Planner(seed, "abstract", p, city, FOOTPRINTS);
                V3Planner rare = new V3Planner(seed, "abstract", p, city, LARGE_FOOTPRINTS);
                for (int dz = 0; dz <= 2; dz++) {
                    for (int dx = 0; dx <= 2; dx++) {
                        boolean reserve = period == 1 || period == 2
                                && Math.floorMod(Hash.coords(Hash.dimension(seed, "rare"), dx, dz), 2) == 0;
                        V3Planner selected = reserve ? rare : ordinary;
                        V3Plan plan = selected.plan(new DistrictKey(dx, dz));
                        localCells += plan.count(V3RoadType.TERTIARY);
                        boolean eligible = false;
                        boolean retained = false;
                        for (int az = 0; az < plan.size(); az += p.areaSize()) {
                            for (int ax = 0; ax < plan.size(); ax += p.areaSize()) {
                                int x0 = dx * plan.size() + ax;
                                int z0 = dz * plan.size() + az;
                                eligible |= hasAxisFreeRectangle(selected, city, x0, z0, p.areaSize(), 9);
                                retained |= hasRectangle(plan, city, x0, z0, p.areaSize(), 9, false);
                            }
                        }
                        if (eligible) eligibleUnits++;
                        if (retained) retainedUnits++;
                    }
                }
            }
            System.out.printf("rare period=%d local=%d 9x9 units=%d/%d%n",
                    period, localCells, retainedUnits, eligibleUnits);
        }
    }

    private static void extreme() {
        V3Params p = V3Params.selectedForDefaultArea();
        System.out.println("standard: " + measure(p, (x, z) -> V3Facts.city(0), 0, 0, 2, 2));
        System.out.println("large:    " + measure(p, (x, z) -> V3Facts.city(0),
                0, 0, 2, 2, LARGE_FOOTPRINTS));
        System.out.println("edge:     " + measure(p, (x, z) -> x >= 0 && x < 48 && z >= 0
                && z < 53 ? V3Facts.city(0) : V3Facts.EMPTY,
                -1, -1, 1, 1, LARGE_FOOTPRINTS));
        System.out.println("obstacle: " + measure(p, (x, z) -> x >= 9 && x <= 11 && z >= 9
                && z <= 11 ? V3Facts.blocked(0) : V3Facts.city(0),
                0, 0, 1, 1, LARGE_FOOTPRINTS));
    }

    private static void grid(boolean single) {
        V3Params p = V3Params.selectedForDefaultArea();
        if (single) {
            p = new V3Params(p.areaSize(), p.supercellFactor(), p.minCollectors(),
                    p.maxCollectors(), p.minAxisGap(), p.mergeChance(), p.spurDepth(),
                    p.throughDepth(), p.maxSpurLength(), p.detourRadius(), p.maxDetours(),
                    p.localCandidates(), 1, p.extraLocalDepth(), p.algorithmVersion());
        }
        System.out.println((single ? "grid-single: " : "grid: ")
                + measure(p, (x, z) -> V3Facts.city(0), 0, 0, 3, 3));
    }

    private static void sweep() {
        for (int factor : new int[] {2, 3}) {
            for (int collectors : new int[] {1, 2}) {
                for (double merge : new double[] {0, 0.4, 0.7}) {
                    for (int through : new int[] {5, 6, 7}) {
                        V3Params p = new V3Params(10, factor, 1, collectors, 6, merge,
                                3, through, 4, 2, 6, 5, 2, 10, V3Params.CURRENT_VERSION);
                        Metrics open = measure(p, (x, z) -> V3Facts.city(0), 0, 0, 2, 2);
                        Metrics edge = measure(p, (x, z) -> x >= 0 && x < 48 && z >= 0
                                && z < 53 ? V3Facts.city(0) : V3Facts.EMPTY, -1, -1, 1, 1);
                        Metrics obstacle = measure(p, (x, z) -> {
                            if (x >= 9 && x <= 11 && z >= 9 && z <= 11) return V3Facts.blocked(0);
                            if (x == 22 && z >= 2 && z <= 8) return V3Facts.blocked(0);
                            return V3Facts.city(0);
                        }, 0, 0, 1, 1);
                        System.out.printf("K=%d C=1..%d merge=%.1f through=%d | open %s | edge %s | obstacle %s%n",
                                factor, collectors, merge, through, open, edge, obstacle);
                    }
                }
            }
        }
    }

    private static Metrics measure(V3Params p, V3FactSource source,
                                   int minDx, int minDz, int maxDx, int maxDz) {
        return measure(p, source, minDx, minDz, maxDx, maxDz, FOOTPRINTS);
    }

    private static Metrics measure(V3Params p, V3FactSource source,
                                   int minDx, int minDz, int maxDx, int maxDz,
                                   List<V3Footprint> footprints) {
        Metrics m = new Metrics();
        for (long seed : new long[] {0, 1, 31, 101}) {
            V3Planner planner = new V3Planner(seed, "abstract", p, source, footprints);
            for (int dz = minDz; dz <= maxDz; dz++) {
                for (int dx = minDx; dx <= maxDx; dx++) {
                    V3Plan plan = planner.plan(new DistrictKey(dx, dz));
                    int size = plan.size();
                    m.cells += size * size;
                    m.primary += plan.count(V3RoadType.PRIMARY);
                    m.secondary += plan.count(V3RoadType.SECONDARY);
                    m.tertiary += plan.count(V3RoadType.TERTIARY);
                    m.closed += plan.stats().preLocalClosedAreas();
                    m.open += plan.stats().preLocalOpenAreas();
                    if (plan.stats().preLocalClosedAreas() > 0) {
                        m.minFace = Math.min(m.minFace, plan.stats().minPreLocalClosedArea());
                        m.maxFace = Math.max(m.maxFace, plan.stats().maxPreLocalClosedArea());
                    }
                    m.through += plan.stats().localThrough();
                    m.spurs += plan.stats().localSpurs();
                    m.cross += plan.stats().localCrossFace();
                    m.crossTwoAreas += plan.stats().crossTwoAreas();
                    m.crossThreeAreas += plan.stats().crossThreeAreas();
                    m.crossDeepTwo += plan.stats().crossDeepTwo();
                    m.crossDeepThree += plan.stats().crossDeepThree();
                    m.crossMinOne += plan.stats().crossMinOne();
                    m.crossMinTwo += plan.stats().crossMinTwo();
                    m.crossMinThree += plan.stats().crossMinThree();
                    m.crossMinFourPlus += plan.stats().crossMinFourPlus();
                    m.crossScoreTies += plan.stats().crossScoreTies();
                    m.bent += plan.stats().localBent();
                    m.templates += plan.stats().localTemplateChecks();
                    m.residualChecks += plan.stats().localResidualChecks();
                    m.merges += plan.stats().mergedSegments();
                    m.lostParcels += plan.stats().lostParcelAreas();
                    m.samples += plan.stats().factSamples();
                    boolean anyEligible7 = false;
                    boolean anyRetained7 = false;
                    boolean anyEligible9 = false;
                    boolean anyRetained9 = false;
                    for (int az = 0; az < size; az += p.areaSize()) {
                        for (int ax = 0; ax < size; ax += p.areaSize()) {
                            int x0 = dx * size + ax;
                            int z0 = dz * size + az;
                            for (int width : new int[] {7, 9}) {
                                if (hasRectangle(plan, source, x0, z0, p.areaSize(), width, true)) {
                                    if (width == 7) {
                                        m.eligible7++;
                                        anyEligible7 = true;
                                    } else {
                                        m.eligible9++;
                                        anyEligible9 = true;
                                    }
                                    if (hasRectangle(plan, source, x0, z0, p.areaSize(), width, false)) {
                                        if (width == 7) {
                                            m.retained7++;
                                            anyRetained7 = true;
                                        } else {
                                            m.retained9++;
                                            anyRetained9 = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (anyEligible7) m.eligibleUnits7++;
                    if (anyRetained7) m.retainedUnits7++;
                    if (anyEligible9) m.eligibleUnits9++;
                    if (anyRetained9) m.retainedUnits9++;
                    for (int z = dz * size; z < (dz + 1) * size; z++) {
                        for (int x = dx * size; x < (dx + 1) * size; x++) {
                            if (plan.roadTypeAt(x, z).isRoad() && Direction.count(plan.edgeMaskAt(x, z)) == 1
                                    && plan.roadTypeAt(x, z) != V3RoadType.TERTIARY) m.highEnds++;
                        }
                    }
                }
            }
        }
        return m;
    }

    /** The default-area parameter block must stay the one the abstract evidence was measured with. */
    static void verifyParameters() {
        V3Params p = V3Params.selectedForDefaultArea();
        check(p.areaSize() == 10 && p.supercellFactor() == 3 && p.supercellSize() == 30,
                "default V3 scale changed: M=" + p.areaSize() + " K=" + p.supercellFactor());
        check(p.minCollectors() == 1 && p.maxCollectors() == 1, "selected collector count changed");
        check(p.algorithmVersion() == V3Params.CURRENT_VERSION,
                "default parameters do not carry the current version salt");
    }

    /** A fact source plus a human-readable name, so a failure names the world it came from. */
    private record World(String label, V3FactSource facts) { }

    /**
     * Four fact worlds exercised for every graph invariant: open, bounded, entrance+obstacle, sparse obstacles.
     */
    private static World[] worlds() {
        return new World[] {
                new World("open city", (x, z) -> V3Facts.city(0)),
                new World("bounded city", (x, z) -> x > -28 && x < 48 && z > -32 && z < 52
                        ? V3Facts.city(0) : V3Facts.EMPTY),
                new World("entrance and obstacle", (x, z) -> (x == 0 && z == 8) || (x == 3 && z == 3)
                        ? V3Facts.entrance(0)
                        : x == 8 && z >= 4 && z <= 7 ? V3Facts.blocked(0) : V3Facts.city(0)),
                new World("sparse obstacles", (x, z) -> (x == 29 && z == 8) || (x == 30 && z == 12)
                        || (x == -1 && z == -8) ? V3Facts.blocked(0) : V3Facts.city(0))
        };
    }

    /**
     * Negative coordinates, cross-border edge agreement, one-edge reasons, local-road degree,
     * and cache eviction; run over {@link #worlds()}.
     */
    static void verifyWorldInvariants() {
        V3Params p = V3Params.selectedForDefaultArea();
        for (World world : worlds()) {
            V3FactSource source = world.facts();
            V3Planner evicting = new V3Planner(31, "abstract", p, source, FOOTPRINTS, 1);
            long original = evicting.plan(new DistrictKey(0, 0)).digest();
            evicting.plan(new DistrictKey(-1, -1));
            check(original == evicting.plan(new DistrictKey(0, 0)).digest(),
                    "eviction changed plan for " + world.label());
            V3Planner planner = new V3Planner(31, "abstract", p, source, LARGE_FOOTPRINTS, 32);
            for (int z = -35; z <= 35; z++) {
                for (int x = -35; x <= 35; x++) {
                    V3CellInfo info = planner.infoAt(x, z);
                    if (info.roadType() == V3RoadType.TERTIARY) {
                        check(Direction.count(info.edgeMask()) <= 2,
                                "branching local road at " + x + "," + z + " for " + world.label());
                    }
                    if (info.roadType().isRoad() && Direction.count(info.edgeMask()) == 1) {
                        check(info.endReason() != V3EndReason.NONE,
                                "unexplained road end at " + x + "," + z + " for " + world.label());
                    }
                    for (Direction direction : Direction.VALUES) {
                        V3CellInfo adjacent = planner.infoAt(x + direction.dx(), z + direction.dz());
                        check(info.edge(direction) == adjacent.edge(direction.opposite()),
                                "seam/edge mismatch at " + x + "," + z + " " + direction
                                        + " for " + world.label());
                        check(!info.edge(direction).isRoad() || (info.roadType().isRoad()
                                        && adjacent.roadType().isRoad()),
                                "edge to non-road at " + x + "," + z + " " + direction
                                        + " for " + world.label());
                    }
                }
            }
        }
    }

    /** Open map: three classes present, no lost parcel area, and a 3x3/7x7/9x9 candidate in every supercell. */
    static void verifyOpenMapContent() {
        V3Params p = V3Params.selectedForDefaultArea();
        V3Planner stations = new V3Planner(31, "abstract", p,
                (x, z) -> (x == 0 && z == 8) || (x == 3 && z == 3)
                        ? V3Facts.entrance(0) : V3Facts.city(0), FOOTPRINTS);
        check(stations.infoAt(0, 8).roadWinsStation(), "mandatory axis lost to station entrance");
        check(!stations.infoAt(3, 3).roadWinsStation(), "off-axis entrance removed");
        V3Planner open = new V3Planner(31, "abstract", p, (x, z) -> V3Facts.city(0), LARGE_FOOTPRINTS);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                V3Plan plan = open.plan(new DistrictKey(dx, dz));
                check(plan.stats().lostParcelAreas() == 0, "open map lost multi-building area");
                check(plan.count(V3RoadType.PRIMARY) > 0 && plan.count(V3RoadType.SECONDARY) > 0
                        && plan.count(V3RoadType.TERTIARY) > 0, "missing road class");
                boolean found7 = false;
                boolean found9 = false;
                for (int az = 0; az < plan.size(); az += p.areaSize()) {
                    for (int ax = 0; ax < plan.size(); ax += p.areaSize()) {
                        int x0 = dx * plan.size() + ax;
                        int z0 = dz * plan.size() + az;
                        V3FactSource city = (x, z) -> V3Facts.city(0);
                        check(hasRectangle(plan, city, x0, z0, p.areaSize(), 3, false), "no 3x3 parcel");
                        found7 |= hasRectangle(plan, city, x0, z0, p.areaSize(), 7, false);
                        found9 |= hasRectangle(plan, city, x0, z0, p.areaSize(), 9, false);
                    }
                }
                check(found7 && found9, "open supercell has no 7x7/9x9 candidate");
            }
        }
    }

    /**
     * Conditional fallback: whenever a supercell still has a 7x7/9x9 area free of primary and
     * secondary roads, the planner must have kept a road-free parcel there. Local roads may be
     * withdrawn, primary/secondary may not, and terrain is never invented.
     */
    static void verifyFeasibleParcels() {
        V3Params p = V3Params.selectedForDefaultArea();
        for (World world : feasibleParcelSources()) {
            V3FactSource source = world.facts();
            for (long seed : new long[] {0, 1, 31, 101}) {
                V3Planner planner = new V3Planner(seed, "abstract", p, source, LARGE_FOOTPRINTS);
                for (int dz = 0; dz <= 1; dz++) {
                    for (int dx = 0; dx <= 1; dx++) {
                        V3Plan plan = planner.plan(new DistrictKey(dx, dz));
                        for (int width : new int[] {7, 9}) {
                            boolean spineHasParcel = false;
                            boolean planHasParcel = false;
                            for (int az = 0; az < plan.size(); az += p.areaSize()) {
                                for (int ax = 0; ax < plan.size(); ax += p.areaSize()) {
                                    int x0 = dx * plan.size() + ax;
                                    int z0 = dz * plan.size() + az;
                                    spineHasParcel |= hasHighRoadFreeRectangle(plan, source,
                                            x0, z0, p.areaSize(), width, width);
                                    planHasParcel |= hasRectangle(plan, source, x0, z0,
                                            p.areaSize(), width, false);
                                }
                            }
                            check(!spineHasParcel || planHasParcel,
                                    "lost every " + width + "x" + width
                                            + " candidate in a feasible supercell for " + world.label()
                                            + " at D[" + dx + "," + dz + "] seed=" + seed);
                        }
                    }
                }
            }
        }
    }

    private static World[] feasibleParcelSources() {
        return new World[] {
                new World("open city", (x, z) -> V3Facts.city(0)),
                new World("bounded city", (x, z) -> x >= 0 && x < 48 && z >= 0 && z < 53
                        ? V3Facts.city(0) : V3Facts.EMPTY),
                new World("central obstacle", (x, z) -> x >= 9 && x <= 11 && z >= 9 && z <= 11
                        ? V3Facts.blocked(0) : V3Facts.city(0)),
                new World("axis entrance", (x, z) -> x == 0 && z == 8
                        ? V3Facts.entrance(0) : V3Facts.city(0)),
                new World("wide obstacle", (x, z) -> x >= 13 && x <= 16 && z >= 5 && z <= 13
                        ? V3Facts.blocked(0) : V3Facts.city(0)),
                new World("comb obstacles", (x, z) -> Math.floorMod(x, 17) == 0
                        && Math.floorMod(z, 19) < 7 ? V3Facts.blocked(0) : V3Facts.city(0))
        };
    }

    /** The conditional fallback must actually fire on an open map, and count the cells it withdrew. */
    static void verifyRareRescue() {
        V3Params p = V3Params.selectedForDefaultArea();
        int rescuedCells = 0;
        for (long seed : new long[] {0, 1, 31, 101}) {
            V3Planner rareOpen = new V3Planner(seed, "abstract", p,
                    (x, z) -> V3Facts.city(0), LARGE_FOOTPRINTS);
            for (int dz = 0; dz <= 2; dz++) {
                for (int dx = 0; dx <= 2; dx++) {
                    V3Plan plan = rareOpen.plan(new DistrictKey(dx, dz));
                    check(plan.stats().rareSuppressedCells() >= plan.stats().rareSuppressedLocals(),
                            "rare rescue did not count removed road cells");
                    rescuedCells += plan.stats().rareSuppressedCells();
                }
            }
        }
        check(rescuedCells > 0, "rare parcel rescue was not exercised");
    }

    static void verifyContinuousLocals() {
        V3Planner planner = new V3Planner(31, "abstract", V3Params.selectedForDefaultArea(),
                (x, z) -> V3Facts.city(0), LARGE_FOOTPRINTS);
        int bent = 0;
        int cross = 0;
        for (int dz = 0; dz <= 2; dz++) {
            for (int dx = 0; dx <= 2; dx++) {
                V3Plan plan = planner.plan(new DistrictKey(dx, dz));
                bent += plan.stats().localBent();
                cross += plan.stats().localCrossFace();
            }
        }
        check(bent > 0, "finite-bend local roads were not exercised");
        check(cross > 0, "cross-face local roads were not exercised");
    }

    /** A large-only asset list must not remove the last parcel or every local road. */
    static void verifyLargeOnlyFootprint() {
        V3Params p = V3Params.selectedForDefaultArea();
        V3Planner onlyLarge = new V3Planner(31, "abstract", p,
                (x, z) -> V3Facts.city(0), List.of(new V3Footprint(9, 9)));
        V3Plan onlyLargePlan = onlyLarge.plan(new DistrictKey(0, 0));
        boolean largeCandidate = false;
        for (int az = 0; az < onlyLargePlan.size(); az += p.areaSize()) {
            for (int ax = 0; ax < onlyLargePlan.size(); ax += p.areaSize()) {
                largeCandidate |= hasRectangle(onlyLargePlan, (x, z) -> V3Facts.city(0),
                        ax, az, p.areaSize(), 9, false);
            }
        }
        check(largeCandidate && onlyLargePlan.count(V3RoadType.TERTIARY) > 0,
                "a large-only asset list removed the last parcel or all local roads");
    }

    /**
     * Interior station entrances on a mandatory axis: the planner must first try a bounded detour
     * (station survives, axis reconnects, the gap is recorded as connected) and only fall back to
     * road priority when no detour exists.
     */
    static void verifyStationChoices(V3Params p) {
        int collector = new V3Planner(31, "abstract", p, (x, z) -> V3Facts.city(0), List.of())
                .collectorOffsets(0, true)[0];
        String at = " at " + collector + ",4";
        V3FactSource open = (x, z) -> x == collector && z == 4
                ? V3Facts.entrance(0) : V3Facts.city(0);
        V3Plan detoured = new V3Planner(31, "abstract", p, open, List.of())
                .plan(new DistrictKey(0, 0));
        check(!detoured.roadWinsStationAt(collector, 4),
                "interior station was removed before detour" + at);
        check(detoured.roadTypeAt(collector, 4) == V3RoadType.NONE,
                "detoured station became a road" + at);
        check(connectedWithin(detoured, collector, 3, collector, 5),
                "station detour did not reconnect its axis" + at);
        check(detoured.axisGaps().stream().anyMatch(g -> g.reason() == V3EndReason.STATION
                && g.connectedAround()), "detoured station gap was not recorded" + at);

        V3FactSource noDetour = (x, z) -> {
            if (x == collector && z == 4) return V3Facts.entrance(0);
            if (Math.abs(x - collector) <= 2 && x != collector && Math.abs(z - 4) <= 2) {
                return V3Facts.blocked(0);
            }
            return V3Facts.city(0);
        };
        V3Plan won = new V3Planner(31, "abstract", p, noDetour, List.of())
                .plan(new DistrictKey(0, 0));
        check(won.roadWinsStationAt(collector, 4),
                "blocked detour did not give road priority" + at);
        check(won.roadTypeAt(collector, 4) == V3RoadType.SECONDARY,
                "road-priority station did not complete the collector" + at);
    }

    /**
     * An axis gap blocked by predefined buildings must be reported with its reason, and every
     * internal gap's published {@code connectedAround} must agree with the final explicit graph.
     */
    static void verifyGapReasons(V3Params p) {
        int collector = new V3Planner(31, "abstract", p, (x, z) -> V3Facts.city(0), List.of())
                .collectorOffsets(0, true)[0];
        V3Plan blocked = new V3Planner(31, "abstract", p,
                (x, z) -> x == collector && z >= 4 && z <= 8
                        ? V3Facts.predefinedBuilding(0) : V3Facts.city(0), List.of())
                .plan(new DistrictKey(0, 0));
        check(blocked.axisGaps().stream().anyMatch(g -> g.reason() == V3EndReason.PREDEFINED
                        && g.startZ() <= 4 && g.endZ() >= 8),
                "predefined axis gap was not recorded for blocked run z=4..8 at x=" + collector);
        for (V3AxisGap gap : blocked.axisGaps()) {
            if (!gap.internal()) continue;
            int beforeX = gap.startX() - (gap.startX() == gap.endX() ? 0 : 1);
            int beforeZ = gap.startZ() - (gap.startZ() == gap.endZ() ? 0 : 1);
            int afterX = gap.endX() + (gap.startX() == gap.endX() ? 0 : 1);
            int afterZ = gap.endZ() + (gap.startZ() == gap.endZ() ? 0 : 1);
            check(gap.connectedAround() == connectedWithin(blocked, beforeX, beforeZ, afterX, afterZ),
                    "axis gap connectivity differs from the final graph for gap "
                            + gap.startX() + "," + gap.startZ() + " -> "
                            + gap.endX() + "," + gap.endZ() + " (" + gap.reason() + ")");
        }
    }

    /**
     * Non-nested rare footprints (9x9, 10x4): the conditional fallback must not lose a candidate
     * that the primary/secondary spine still allows, and eviction must not change the outcome.
     */
    static void verifyNonNestedRareFootprints(V3Params p) {
        V3FactSource city = (x, z) -> V3Facts.city(0);
        List<V3Footprint> shapes = List.of(new V3Footprint(9, 9),
                new V3Footprint(10, 4), new V3Footprint(3, 3));
        for (long seed : new long[] {0, 1, 31, 101}) {
            V3Planner planner = new V3Planner(seed, "abstract", p, city, shapes, 1);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    DistrictKey key = new DistrictKey(dx, dz);
                    V3Plan plan = planner.plan(key);
                    long digest = plan.digest();
                    for (V3Footprint shape : shapes.subList(0, 2)) {
                        boolean highRoadFree = false;
                        boolean retained = false;
                        for (int az = 0; az < plan.size(); az += p.areaSize()) {
                            for (int ax = 0; ax < plan.size(); ax += p.areaSize()) {
                                int x0 = dx * plan.size() + ax;
                                int z0 = dz * plan.size() + az;
                                highRoadFree |= hasHighRoadFreeRectangle(plan, city,
                                        x0, z0, p.areaSize(), shape.x(), shape.z());
                                retained |= hasRectangle(plan, city, x0, z0, p.areaSize(),
                                        shape.x(), shape.z(), false);
                            }
                        }
                        check(!highRoadFree || retained,
                                "non-nested rare footprint lost: " + shape + " in " + key + " seed=" + seed);
                    }
                    planner.plan(new DistrictKey(dx + 3, dz + 3));
                    check(digest == planner.plan(key).digest(),
                            "rare rescue changed after eviction in " + key + " seed=" + seed);
                }
            }
        }
    }

    /**
     * Render transition ports: every arm stays inside its supercell, is mirrored by the neighbour,
     * and is a <em>local</em> (TERTIARY) logical edge -- a transition never upgrades an edge class.
     */
    static void verifyTransitionPorts(V3Params p) {
        V3Plan plan = new V3Planner(31, "abstract", p, (x, z) -> V3Facts.city(0), List.of())
                .plan(new DistrictKey(0, 0));
        boolean found = false;
        for (int z = 0; z < plan.size(); z++) {
            for (int x = 0; x < plan.size(); x++) {
                V3CellInfo info = plan.infoAt(x, z);
                for (Direction direction : Direction.VALUES) {
                    if (!info.transitionArm(direction)) continue;
                    int nx = x + direction.dx();
                    int nz = z + direction.dz();
                    check(nx >= 0 && nz >= 0 && nx < plan.size() && nz < plan.size(),
                            "local transition crosses a supercell at " + x + "," + z + " " + direction);
                    V3CellInfo neighbor = plan.infoAt(nx, nz);
                    check(neighbor.transitionArm(direction.opposite()),
                            "asymmetric transition port at " + x + "," + z + " " + direction);
                    check(info.edge(direction) == V3RoadType.TERTIARY,
                            "render transition changed the logical edge class at "
                                    + x + "," + z + " " + direction + ": edge=" + info.edge(direction));
                    found = true;
                }
            }
        }
        check(found, "open map has no local road transition");
    }

    private static boolean connectedWithin(V3Plan plan, int ax, int az, int bx, int bz) {
        int size = plan.size();
        boolean[] seen = new boolean[size * size];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(az * size + ax);
        seen[az * size + ax] = true;
        while (!queue.isEmpty()) {
            int at = queue.removeFirst();
            int x = at % size;
            int z = at / size;
            if (x == bx && z == bz) return true;
            int mask = plan.edgeMaskAt(x, z);
            for (Direction direction : Direction.VALUES) {
                if ((mask & direction.bit()) == 0) continue;
                int nx = x + direction.dx();
                int nz = z + direction.dz();
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                int next = nz * size + nx;
                if (!seen[next]) {
                    seen[next] = true;
                    queue.addLast(next);
                }
            }
        }
        return false;
    }

    private static boolean hasAxisFreeRectangle(V3Planner planner, V3FactSource source,
                                                int x0, int z0, int area, int width) {
        for (int z = z0; z <= z0 + area - width; z++) {
            for (int x = x0; x <= x0 + area - width; x++) {
                boolean free = true;
                for (int dz = 0; dz < width; dz++) {
                    for (int dx = 0; dx < width; dx++) {
                        free &= source.factsAt(x + dx, z + dz).parcelPavable()
                                && !planner.axisTypeAt(x + dx, z + dz).isRoad();
                    }
                }
                if (free) return true;
            }
        }
        return false;
    }

    private static boolean hasRectangle(V3Plan plan, V3FactSource source,
                                        int x0, int z0, int area, int width, boolean ignoreRoad) {
        return hasRectangle(plan, source, x0, z0, area, width, width, ignoreRoad);
    }

    private static boolean hasHighRoadFreeRectangle(V3Plan plan, V3FactSource source,
                                                    int x0, int z0, int area, int width, int height) {
        for (int z = z0; z <= z0 + area - height; z++) {
            for (int x = x0; x <= x0 + area - width; x++) {
                boolean free = true;
                for (int dz = 0; dz < height; dz++) {
                    for (int dx = 0; dx < width; dx++) {
                        V3RoadType road = plan.roadTypeAt(x + dx, z + dz);
                        free &= source.factsAt(x + dx, z + dz).parcelPavable()
                                && road != V3RoadType.PRIMARY && road != V3RoadType.SECONDARY;
                    }
                }
                if (free) return true;
            }
        }
        return false;
    }

    private static boolean hasRectangle(V3Plan plan, V3FactSource source,
                                        int x0, int z0, int area, int width, int height,
                                        boolean ignoreRoad) {
        for (int z = z0; z <= z0 + area - height; z++) {
            for (int x = x0; x <= x0 + area - width; x++) {
                boolean free = true;
                for (int dz = 0; dz < height; dz++) {
                    for (int dx = 0; dx < width; dx++) {
                        free &= source.factsAt(x + dx, z + dz).parcelPavable()
                                && (ignoreRoad || !plan.roadTypeAt(x + dx, z + dz).isRoad());
                    }
                }
                if (free) return true;
            }
        }
        return false;
    }

    /**
     * The single assertion primitive shared by the CLI and {@code V3AbstractCasesTest}.
     * It throws {@link AssertionError} so JUnit reports it as a failure, not as an error.
     */
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Metrics {
        int cells, primary, secondary, tertiary, closed, open, through, spurs, cross, bent;
        int crossTwoAreas, crossThreeAreas;
        int crossDeepTwo, crossDeepThree;
        int crossMinOne, crossMinTwo, crossMinThree, crossMinFourPlus;
        int crossScoreTies;
        int templates, residualChecks;
        int merges, lostParcels, highEnds, samples, minFace = Integer.MAX_VALUE, maxFace;
        int eligible7, retained7, eligible9, retained9;
        int eligibleUnits7, retainedUnits7, eligibleUnits9, retainedUnits9;

        @Override
        public String toString() {
            return String.format("road=%d/%d P/S/T=%d/%d/%d faces=%d+%d area=%d..%d local=%d/%d cross=%d crossAreas2/3=%d/%d crossDeep2/3=%d/%d min1/2/3/4+=%d/%d/%d/%d scoreTies=%d bent=%d templates=%d residual=%d merge=%d end=%d parcelLoss=%d 7x7=%d/%d units=%d/%d 9x9=%d/%d units=%d/%d samples=%d",
                    primary + secondary + tertiary, cells, primary, secondary, tertiary,
                    closed, open, minFace == Integer.MAX_VALUE ? 0 : minFace, maxFace,
                    through, spurs, cross, crossTwoAreas, crossThreeAreas,
                    crossDeepTwo, crossDeepThree, crossMinOne, crossMinTwo, crossMinThree,
                    crossMinFourPlus, crossScoreTies, bent, templates, residualChecks,
                    merges, highEnds, lostParcels,
                    retained7, eligible7, retainedUnits7, eligibleUnits7,
                    retained9, eligible9, retainedUnits9, eligibleUnits9, samples);
        }
    }
}
