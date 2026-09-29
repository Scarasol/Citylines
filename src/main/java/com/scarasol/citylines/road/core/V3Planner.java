package com.scarasol.citylines.road.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure V3 road planner. No Minecraft or Lost Cities state is read here.
 *
 * <p>One owner computes all three classes before building selection. A border edge
 * depends only on frozen nearby facts and global strip axes; optional
 * merging and local roads never change a cross-border edge. This makes plans independent
 * of first-query order without recursively building neighbouring supercells.
 */
public final class V3Planner {
    public static final int DEFAULT_PLAN_CAPACITY = 128;
    private static final int MAX_LOCAL_POCKETS = 3;
    private static final int MAX_LOCAL_PORTS_PER_SIDE = 4;
    private static final int MAX_LOCAL_TEMPLATES = 64;
    private static final int MAX_CROSS_HOSTS_PER_PAIR = 5;
    private static final int MAX_CROSS_LEG_TEMPLATES = 6;
    private static final int MAX_CROSS_EXTRA_CELLS = 2;
    private static final int MAX_BOUNDARY_DETOUR_OFFSET = 2;
    private static final int LAYOUT_SALT_VERSION = 4;

    private static final long X_STRIP_SALT = 0x4f6e55418223a113L;
    private static final long Z_STRIP_SALT = 0x51db836e127acf35L;
    private static final long MERGE_SALT = 0x259bf4724c987a0dL;
    private static final V3RoadType[] TYPES = V3RoadType.values();

    private final long seed;
    private final String dimensionId;
    private final V3Params params;
    private final V3FactSource facts;
    private final List<V3Footprint> footprints;
    private final long salt;
    private final int capacity;
    private final LinkedHashMap<DistrictKey, V3Plan> plans;

    public V3Planner(long seed, String dimensionId, V3Params params, V3FactSource facts,
                     List<V3Footprint> footprints) {
        this(seed, dimensionId, params, facts, footprints, DEFAULT_PLAN_CAPACITY);
    }

    public V3Planner(long seed, String dimensionId, V3Params params, V3FactSource facts,
                     List<V3Footprint> footprints, int capacity) {
        if (dimensionId == null || params == null || facts == null || footprints == null || capacity < 1) {
            throw new IllegalArgumentException("V3 planner needs frozen inputs and positive capacity");
        }
        this.seed = seed;
        this.dimensionId = dimensionId;
        this.params = params;
        this.facts = facts;
        this.footprints = footprints.stream().distinct()
                .sorted(Comparator.comparingInt(V3Footprint::area).reversed()
                        .thenComparingInt(V3Footprint::x)
                        .thenComparingInt(V3Footprint::z))
                .toList();
        for (V3Footprint footprint : this.footprints) {
            if (footprint.x() > params.areaSize() || footprint.z() > params.areaSize()) {
                throw new IllegalArgumentException("footprint exceeds a MultiChunk area: " + footprint);
            }
        }
        // A policy revision must not move every already selected collector axis.
        this.salt = Hash.combine(Hash.dimension(seed, dimensionId), LAYOUT_SALT_VERSION);
        this.capacity = capacity;
        this.plans = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<DistrictKey, V3Plan> eldest) {
                return size() > V3Planner.this.capacity;
            }
        };
    }

    public V3Params params() {
        return params;
    }

    public long fingerprint() {
        long hash = params.fingerprint(seed, dimensionId);
        for (V3Footprint footprint : footprints) {
            hash = Hash.combine(hash, Hash.combine(footprint.x(), footprint.z()));
        }
        return hash;
    }

    public synchronized V3Plan planAt(int x, int z) {
        return plan(DistrictKey.of(x, z, params.supercellSize()));
    }

    public synchronized V3Plan plan(DistrictKey key) {
        V3Plan existing = plans.get(key);
        if (existing != null) return existing;
        V3Plan computed = new Context(key).build();
        plans.put(key, computed);
        return computed;
    }

    public synchronized V3CellInfo infoAt(int x, int z) {
        return planAt(x, z).infoAt(x, z);
    }

    public synchronized V3RoadType roadTypeAt(int x, int z) {
        return planAt(x, z).roadTypeAt(x, z);
    }

    public synchronized int cachedPlans() {
        return plans.size();
    }

    static int distinctLocalAreas(int[] cells, V3Params params) {
        int occupied = 0;
        int size = params.supercellSize();
        for (int at : cells) {
            int areaX = (at % size) / params.areaSize();
            int areaZ = (at / size) / params.areaSize();
            occupied |= 1 << (areaZ * params.supercellFactor() + areaX);
        }
        return Integer.bitCount(occupied);
    }

    static AreaScore localAreaScore(int[] cells, V3Params params) {
        int size = params.supercellSize();
        int[] counts = new int[params.supercellFactor() * params.supercellFactor()];
        for (int at : cells) {
            int areaX = (at % size) / params.areaSize();
            int areaZ = (at / size) / params.areaSize();
            counts[areaZ * params.supercellFactor() + areaX]++;
        }
        int threshold = Math.max(2, (params.areaSize() + 2) / 3);
        int effective = 0;
        int capped = 0;
        int minimum = Integer.MAX_VALUE;
        for (int count : counts) {
            if (count == 0) continue;
            if (count >= threshold) effective++;
            capped += Math.min(count, threshold);
            minimum = Math.min(minimum, count);
        }
        return new AreaScore(effective, capped, minimum == Integer.MAX_VALUE ? 0 : minimum);
    }

    static record AreaScore(int effectiveAreas, int cappedCells, int minimumCells) { }

    /** Globally continuous strip axes; no scan from the world origin is required. */
    public V3RoadType axisTypeAt(int x, int z) {
        int size = params.supercellSize();
        if (Math.floorMod(x, size) == 0 || Math.floorMod(z, size) == 0) {
            return V3RoadType.PRIMARY;
        }
        if (contains(offsets(Math.floorDiv(x, size), true), Math.floorMod(x, size))
                || contains(offsets(Math.floorDiv(z, size), false), Math.floorMod(z, size))) {
            return V3RoadType.SECONDARY;
        }
        return V3RoadType.NONE;
    }

    public int[] collectorOffsets(int strip, boolean xAxis) {
        return offsets(strip, xAxis).clone();
    }

    private int[] offsets(int strip, boolean xAxis) {
        int size = params.supercellSize();
        long key = Hash.combine(salt, xAxis ? X_STRIP_SALT : Z_STRIP_SALT, strip);
        int count = params.minCollectors() + Math.floorMod((int) Hash.mix(key),
                params.maxCollectors() - params.minCollectors() + 1);
        int gaps = count + 1;
        int slack = size - gaps * params.minAxisGap();
        int[] weights = new int[gaps];
        int total = 0;
        for (int i = 0; i < gaps; i++) {
            weights[i] = 1 + Math.floorMod((int) Hash.mix(Hash.combine(key, i)), 3);
            total += weights[i];
        }
        int[] result = new int[count];
        int usedWeight = 0;
        int position = 0;
        for (int i = 0; i < count; i++) {
            int before = slack * usedWeight / total;
            usedWeight += weights[i];
            int after = slack * usedWeight / total;
            position += params.minAxisGap() + after - before;
            result[i] = position;
        }
        return result;
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) if (candidate == value) return true;
        return false;
    }

    private final class Context {
        private final DistrictKey key;
        private final int size = params.supercellSize();
        private final int stride = size + 2;
        private final int originX;
        private final int originZ;
        private final V3Facts[] sample = new V3Facts[stride * stride];
        private final byte[] candidate = new byte[stride * stride];
        private final boolean[] sampleWins = new boolean[stride * stride];
        private final byte[] types = new byte[size * size];
        private final byte[] masks = new byte[size * size];
        private final byte[] reasons = new byte[size * size];
        private final boolean[] stationWins = new boolean[size * size];
        private final boolean[] protectedParcel = new boolean[size * size];
        private final List<V3AxisGap> axisGaps = new ArrayList<>();
        private final List<Local> appliedLocals = new ArrayList<>();
        private Map<Long, V3Facts> seamFacts;
        private int samplesTaken;
        private int detours;
        private int merges;
        private int closedFaces;
        private int openFaces;
        private int minClosedFaceArea = Integer.MAX_VALUE;
        private int maxClosedFaceArea;
        private int throughRoads;
        private int crossRoads;
        private int crossScoreTies;
        private int bentRoads;
        private int localTemplateChecks;
        private int localResidualChecks;
        private int spurRoads;
        private int wonStations;
        private int lostParcelAreas;
        private int budgetExhausted;
        private int droppedIsolated;
        private int noLocalRouteFaces;
        private int unrepairedAxisGaps;
        private int rareSuppressedLocals;
        private int rareSuppressedCells;
        private int rareRescues;
        private int rareNoRawCandidate;
        private int rareAxisBlocked;
        private int rareSpineBlocked;

        Context(DistrictKey key) {
            this.key = key;
            this.originX = key.originX(size);
            this.originZ = key.originZ(size);
        }

        V3Plan build() {
            sampleFacts();
            initialAxes();
            protectParcelRectangles();
            resolveInteriorStations();
            repairShortGaps();
            recordAxisGaps();
            mergeOneSegment();
            addLocalRoads();
            preserveRareParcel();
            dropIsolatedCells();
            refreshAxisGapConnectivity();
            classifyEnds();
            byte[] edges = edgeClasses();
            int crossTwoAreas = 0;
            int crossThreeAreas = 0;
            int crossDeepTwo = 0;
            int crossDeepThree = 0;
            int[] crossMinBuckets = new int[4];
            for (Local local : appliedLocals) {
                if (local.faceB < 0) continue;
                int areas = distinctLocalAreas(local.cells, params);
                if (areas >= 2) crossTwoAreas++;
                if (areas >= 3) crossThreeAreas++;
                AreaScore score = localAreaScore(local.cells, params);
                if (score.effectiveAreas >= 2) crossDeepTwo++;
                if (score.effectiveAreas >= 3) crossDeepThree++;
                crossMinBuckets[Math.min(4, score.minimumCells) - 1]++;
            }
            return new V3Plan(key, size, types, masks, edges, reasons, stationWins, axisGaps,
                    new V3Plan.Stats(samplesTaken, detours, merges, closedFaces, openFaces,
                            closedFaces == 0 ? 0 : minClosedFaceArea, maxClosedFaceArea,
                            throughRoads, spurRoads, wonStations, lostParcelAreas,
                            unrepairedAxisGaps, budgetExhausted, droppedIsolated, noLocalRouteFaces,
                            rareRescues, rareSuppressedLocals, rareSuppressedCells,
                            rareNoRawCandidate, rareAxisBlocked, rareSpineBlocked,
                            crossRoads, bentRoads, localTemplateChecks, localResidualChecks,
                            crossTwoAreas, crossThreeAreas, crossDeepTwo, crossDeepThree,
                            crossMinBuckets[0], crossMinBuckets[1], crossMinBuckets[2],
                            crossMinBuckets[3], crossScoreTies), fingerprint());
        }

        private int index(int x, int z) {
            return z * size + x;
        }

        private int sampledIndex(int x, int z) {
            return (z + 1) * stride + x + 1;
        }

        private V3Facts read(int x, int z) {
            samplesTaken++;
            V3Facts result = facts.factsAt(x, z);
            return result == null ? V3Facts.EMPTY : result;
        }

        private V3Facts fact(int x, int z) {
            if (x >= -1 && x <= size && z >= -1 && z <= size) {
                return sample[sampledIndex(x, z)];
            }
            return read(originX + x, originZ + z);
        }

        private V3RoadType candidateType(int x, int z) {
            return TYPES[candidate[sampledIndex(x, z)] & 0xff];
        }

        private boolean pavable(int x, int z) {
            return fact(x, z).roadPavable(sampleWins[sampledIndex(x, z)]);
        }

        private void sampleFacts() {
            byte[] xAxes = new byte[stride];
            byte[] zAxes = new byte[stride];
            for (int offset = -1; offset <= size; offset++) {
                int worldX = originX + offset;
                int worldZ = originZ + offset;
                xAxes[offset + 1] = axisGrade(worldX, true);
                zAxes[offset + 1] = axisGrade(worldZ, false);
            }
            for (int z = -1; z <= size; z++) {
                for (int x = -1; x <= size; x++) {
                    sample[sampledIndex(x, z)] = read(originX + x, originZ + z);
                }
            }
            for (int z = -1; z <= size; z++) {
                for (int x = -1; x <= size; x++) {
                    V3Facts f = fact(x, z);
                    V3RoadType axis = TYPES[Math.max(xAxes[x + 1], zAxes[z + 1])];
                    boolean wins = f.stationEntrance() && axis.isRoad()
                            && isBoundary(originX + x, originZ + z)
                            && opposingAxisArms(originX + x, originZ + z, f.cityLevel());
                    sampleWins[sampledIndex(x, z)] = wins;
                    candidate[sampledIndex(x, z)] = (byte) (f.roadPavable(wins) ? axis.ordinal() : 0);
                    if (wins && x >= 0 && x < size && z >= 0 && z < size) {
                        stationWins[index(x, z)] = true;
                        wonStations++;
                    }
                }
            }
            repairBoundaryGaps();
        }

        private V3Facts seamFact(int worldX, int worldZ) {
            int x = worldX - originX;
            int z = worldZ - originZ;
            if (x >= -1 && x <= size && z >= -1 && z <= size) return fact(x, z);
            if (seamFacts == null) seamFacts = new HashMap<>();
            long key = ((long) worldX << 32) ^ (worldZ & 0xffffffffL);
            return seamFacts.computeIfAbsent(key, ignored -> read(worldX, worldZ));
        }

        /** Each owner makes the same bounded decision from facts on both sides of a seam. */
        private void repairBoundaryGaps() {
            if (params.detourRadius() == 0 || params.maxDetours() == 0) return;
            int[] xs = offsets(key.dx(), true);
            int[] zs = offsets(key.dz(), false);
            for (int seamZ : new int[] {originZ, originZ + size}) {
                repairVerticalSeam(originX, seamZ, V3RoadType.PRIMARY);
                for (int x : xs) repairVerticalSeam(originX + x, seamZ, V3RoadType.SECONDARY);
            }
            for (int seamX : new int[] {originX, originX + size}) {
                repairHorizontalSeam(seamX, originZ, V3RoadType.PRIMARY);
                for (int z : zs) repairHorizontalSeam(seamX, originZ + z, V3RoadType.SECONDARY);
            }
        }

        private void repairVerticalSeam(int worldX, int seamZ, V3RoadType grade) {
            V3Facts before = seamFact(worldX, seamZ - 1);
            V3Facts after = seamFact(worldX, seamZ);
            if (candidateType(worldX - originX, seamZ - 1 - originZ).isRoad()
                    && candidateType(worldX - originX, seamZ - originZ).isRoad()
                    && before.cityLevel() == after.cityLevel()) return;
            int startZ = before.roadPavable(false) ? seamZ - 1 : seamZ - 2;
            int endZ = after.roadPavable(false) ? seamZ : seamZ + 1;
            V3Facts start = seamFact(worldX, startZ);
            V3Facts end = seamFact(worldX, endZ);
            if (!start.roadPavable(false) || !end.roadPavable(false)
                    || start.cityLevel() != end.cityLevel()) return;
            int level = start.cityLevel();
            int firstSide = (Hash.mix(Hash.coords(salt, worldX, seamZ)) & 1L) == 0 ? -1 : 1;
            for (int distance = 1; distance <= Math.min(params.detourRadius(), MAX_BOUNDARY_DETOUR_OFFSET); distance++) {
                for (int side : new int[] {firstSide, -firstSide}) {
                    int shiftedX = worldX + side * distance;
                    if (Math.floorDiv(shiftedX, size) != Math.floorDiv(worldX, size)) continue;
                    if (!seamRowPavable(worldX, shiftedX, startZ, level)
                            || !seamRowPavable(worldX, shiftedX, endZ, level)) continue;
                    boolean columnPavable = true;
                    for (int z = startZ; z <= endZ; z++) {
                        if (!seamPavable(shiftedX, z, level)) {
                            columnPavable = false;
                            break;
                        }
                    }
                    if (!columnPavable) continue;
                    for (int x = Math.min(worldX, shiftedX); x <= Math.max(worldX, shiftedX); x++) {
                        markSeamCandidate(x, startZ, grade);
                        markSeamCandidate(x, endZ, grade);
                    }
                    for (int z = startZ; z <= endZ; z++) markSeamCandidate(shiftedX, z, grade);
                    return;
                }
            }
        }

        private void repairHorizontalSeam(int seamX, int worldZ, V3RoadType grade) {
            V3Facts before = seamFact(seamX - 1, worldZ);
            V3Facts after = seamFact(seamX, worldZ);
            if (candidateType(seamX - 1 - originX, worldZ - originZ).isRoad()
                    && candidateType(seamX - originX, worldZ - originZ).isRoad()
                    && before.cityLevel() == after.cityLevel()) return;
            int startX = before.roadPavable(false) ? seamX - 1 : seamX - 2;
            int endX = after.roadPavable(false) ? seamX : seamX + 1;
            V3Facts start = seamFact(startX, worldZ);
            V3Facts end = seamFact(endX, worldZ);
            if (!start.roadPavable(false) || !end.roadPavable(false)
                    || start.cityLevel() != end.cityLevel()) return;
            int level = start.cityLevel();
            int firstSide = (Hash.mix(Hash.coords(salt, seamX, worldZ)) & 1L) == 0 ? -1 : 1;
            for (int distance = 1; distance <= Math.min(params.detourRadius(), MAX_BOUNDARY_DETOUR_OFFSET); distance++) {
                for (int side : new int[] {firstSide, -firstSide}) {
                    int shiftedZ = worldZ + side * distance;
                    if (Math.floorDiv(shiftedZ, size) != Math.floorDiv(worldZ, size)) continue;
                    if (!seamColumnPavable(worldZ, shiftedZ, startX, level)
                            || !seamColumnPavable(worldZ, shiftedZ, endX, level)) continue;
                    boolean rowPavable = true;
                    for (int x = startX; x <= endX; x++) {
                        if (!seamPavable(x, shiftedZ, level)) {
                            rowPavable = false;
                            break;
                        }
                    }
                    if (!rowPavable) continue;
                    for (int z = Math.min(worldZ, shiftedZ); z <= Math.max(worldZ, shiftedZ); z++) {
                        markSeamCandidate(startX, z, grade);
                        markSeamCandidate(endX, z, grade);
                    }
                    for (int x = startX; x <= endX; x++) markSeamCandidate(x, shiftedZ, grade);
                    return;
                }
            }
        }

        private boolean seamRowPavable(int ax, int bx, int z, int level) {
            for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x++) {
                if (!seamPavable(x, z, level)) return false;
            }
            return true;
        }

        private boolean seamColumnPavable(int az, int bz, int x, int level) {
            for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++) {
                if (!seamPavable(x, z, level)) return false;
            }
            return true;
        }

        private boolean seamPavable(int worldX, int worldZ, int level) {
            V3Facts f = seamFact(worldX, worldZ);
            return f.cityLevel() == level && f.roadPavable(false) && !f.surfaceRail();
        }

        private void markSeamCandidate(int worldX, int worldZ, V3RoadType grade) {
            int x = worldX - originX;
            int z = worldZ - originZ;
            if (x < -1 || x > size || z < -1 || z > size) return;
            int at = sampledIndex(x, z);
            candidate[at] = (byte) Math.max(candidate[at], grade.ordinal());
        }

        private boolean isBoundary(int x, int z) {
            return Math.floorMod(x, size) == 0 || Math.floorMod(z, size) == 0
                    || Math.floorMod(x, size) == size - 1 || Math.floorMod(z, size) == size - 1;
        }

        private byte axisGrade(int coordinate, boolean xAxis) {
            int local = Math.floorMod(coordinate, size);
            if (local == 0) return (byte) V3RoadType.PRIMARY.ordinal();
            return (byte) (contains(offsets(Math.floorDiv(coordinate, size), xAxis), local)
                    ? V3RoadType.SECONDARY.ordinal() : V3RoadType.NONE.ordinal());
        }

        private boolean opposingAxisArms(int x, int z, int level) {
            return (rawAxisEndpoint(x - 1, z, level) && rawAxisEndpoint(x + 1, z, level))
                    || (rawAxisEndpoint(x, z - 1, level) && rawAxisEndpoint(x, z + 1, level));
        }

        private boolean rawAxisEndpoint(int x, int z, int level) {
            V3Facts f = fact(x - originX, z - originZ);
            return axisTypeAt(x, z).isRoad() && f.cityLevel() == level && f.roadPavable(false);
        }

        private void initialAxes() {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    types[index(x, z)] = candidate[sampledIndex(x, z)];
                }
            }
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    if (types[index(x, z)] == 0) continue;
                    for (Direction direction : Direction.VALUES) {
                        int nx = x + direction.dx();
                        int nz = z + direction.dz();
                        if (candidateType(nx, nz).isRoad()
                                && fact(x, z).cityLevel() == fact(nx, nz).cityLevel()) {
                            masks[index(x, z)] |= (byte) direction.bit();
                        }
                    }
                }
            }
        }

        private void resolveInteriorStations() {
            for (int z = 1; z < size - 1; z++) {
                for (int x = 1; x < size - 1; x++) {
                    V3Facts f = fact(x, z);
                    if (!f.stationEntrance() || !axisTypeAt(originX + x, originZ + z).isRoad()) continue;
                    List<int[]> paths = new ArrayList<>(2);
                    boolean required = false;
                    boolean possible = true;
                    for (Direction direction : new Direction[] {Direction.E, Direction.S}) {
                        int ax = x - direction.dx();
                        int az = z - direction.dz();
                        int bx = x + direction.dx();
                        int bz = z + direction.dz();
                        if (!axisEndpoint(ax, az, f.cityLevel())
                                || !axisEndpoint(bx, bz, f.cityLevel())) continue;
                        required = true;
                        int[] path = findDetour(ax, az, bx, bz);
                        if (path == null) {
                            possible = false;
                            break;
                        }
                        paths.add(path);
                    }
                    if (!required) continue;
                    if (possible && detours + paths.size() <= params.maxDetours()) {
                        for (int[] path : paths) applyDetour(path, axisTypeAt(originX + x, originZ + z));
                    } else {
                        int at = index(x, z);
                        types[at] = (byte) axisTypeAt(originX + x, originZ + z).ordinal();
                        candidate[sampledIndex(x, z)] = types[at];
                        sampleWins[sampledIndex(x, z)] = true;
                        stationWins[at] = true;
                        wonStations++;
                        for (Direction direction : Direction.VALUES) {
                            int nx = x + direction.dx();
                            int nz = z + direction.dz();
                            int next = index(nx, nz);
                            if (types[next] != 0 && fact(nx, nz).cityLevel() == f.cityLevel()) connect(at, next);
                        }
                    }
                }
            }
        }

        private boolean axisEndpoint(int x, int z, int level) {
            return types[index(x, z)] != 0 && fact(x, z).cityLevel() == level;
        }

        private void protectParcelRectangles() {
            int area = params.areaSize();
            List<ParcelArea> areas = new ArrayList<>();
            for (int az = 0; az < size; az += area) {
                for (int ax = 0; ax < size; ax += area) {
                    ParcelArea parcelArea = new ParcelArea(ax, az);
                    for (V3Footprint footprint : footprints) {
                        ParcelOption available = null;
                        for (int z = az; z <= az + area - footprint.z(); z++) {
                            for (int x = ax; x <= ax + area - footprint.x(); x++) {
                                if (!rectangleFits(x, z, footprint, false)) continue;
                                parcelArea.originallyFeasible = true;
                                if (available == null && rectangleFits(x, z, footprint, true)) {
                                    available = new ParcelOption(x, z, footprint);
                                }
                            }
                        }
                        if (available != null) parcelArea.viable.add(available);
                    }
                    areas.add(parcelArea);
                }
            }

            for (ParcelArea parcelArea : areas) {
                ParcelOption choice = null;
                for (ParcelOption option : parcelArea.viable) {
                    if (option.footprint.area() <= 9) {
                        choice = option;
                        break;
                    }
                }
                if (choice != null) reserve(choice);
                else if (parcelArea.viable.isEmpty() && parcelArea.originallyFeasible) lostParcelAreas++;
            }
        }

        private void reserve(ParcelOption option) {
            for (int dz = 0; dz < option.footprint.z(); dz++) {
                for (int dx = 0; dx < option.footprint.x(); dx++) {
                    protectedParcel[index(option.x + dx, option.z + dz)] = true;
                }
            }
        }

        private boolean rectangleFits(int x, int z, V3Footprint footprint, boolean avoidRoad) {
            int level = fact(x, z).cityLevel();
            for (int dz = 0; dz < footprint.z(); dz++) {
                for (int dx = 0; dx < footprint.x(); dx++) {
                    int px = x + dx;
                    int pz = z + dz;
                    V3Facts f = fact(px, pz);
                    if (!f.parcelPavable() || f.cityLevel() != level
                            || (avoidRoad && types[index(px, pz)] != 0)) return false;
                }
            }
            return true;
        }

        private void repairShortGaps() {
            int[] xs = offsets(key.dx(), true);
            int[] zs = offsets(key.dz(), false);
            repairVertical(0, V3RoadType.PRIMARY);
            for (int x : xs) repairVertical(x, V3RoadType.SECONDARY);
            repairHorizontal(0, V3RoadType.PRIMARY);
            for (int z : zs) repairHorizontal(z, V3RoadType.SECONDARY);
        }

        private void repairVertical(int x, V3RoadType grade) {
            for (int z = 0; z < size - 2 && detours < params.maxDetours(); z++) {
                if (types[index(x, z)] == 0 || types[index(x, z + 1)] != 0) continue;
                int end = z + 1;
                while (end < size && types[index(x, end)] == 0 && end - z <= 2) end++;
                if (end >= size || end - z > 3 || types[index(x, end)] == 0) continue;
                repairGap(x, z, x, end, grade);
                z = end - 1;
            }
        }

        private void repairHorizontal(int z, V3RoadType grade) {
            for (int x = 0; x < size - 2 && detours < params.maxDetours(); x++) {
                if (types[index(x, z)] == 0 || types[index(x + 1, z)] != 0) continue;
                int end = x + 1;
                while (end < size && types[index(end, z)] == 0 && end - x <= 2) end++;
                if (end >= size || end - x > 3 || types[index(end, z)] == 0) continue;
                repairGap(x, z, end, z, grade);
                x = end - 1;
            }
        }

        private void repairGap(int ax, int az, int bx, int bz, V3RoadType grade) {
            if (roadPath(ax, az, bx, bz)) return;
            int[] path = findDetour(ax, az, bx, bz);
            if (path != null) applyDetour(path, grade);
        }

        private int[] findDetour(int ax, int az, int bx, int bz) {
            int radius = params.detourRadius();
            if (radius == 0 || fact(ax, az).cityLevel() != fact(bx, bz).cityLevel()) return null;
            int minX = Math.max(0, Math.min(ax, bx) - radius);
            int maxX = Math.min(size - 1, Math.max(ax, bx) + radius);
            int minZ = Math.max(0, Math.min(az, bz) - radius);
            int maxZ = Math.min(size - 1, Math.max(az, bz) + radius);
            int[] parent = new int[size * size];
            Arrays.fill(parent, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            int start = index(ax, az);
            int finish = index(bx, bz);
            parent[start] = start;
            queue.add(start);
            while (!queue.isEmpty() && parent[finish] < 0) {
                int at = queue.removeFirst();
                int x = at % size;
                int z = at / size;
                for (Direction direction : Direction.VALUES) {
                    int nx = x + direction.dx();
                    int nz = z + direction.dz();
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                    int next = index(nx, nz);
                    if ((nx == 0 || nz == 0 || nx == size - 1 || nz == size - 1)
                            && types[next] == 0) continue;
                    if (parent[next] >= 0 || protectedParcel[next] || !pavable(nx, nz)
                            || fact(nx, nz).cityLevel() != fact(ax, az).cityLevel()) continue;
                    parent[next] = at;
                    queue.addLast(next);
                }
            }
            if (parent[finish] < 0) return null;
            List<Integer> path = new ArrayList<>();
            for (int at = finish; at != start; at = parent[at]) path.add(at);
            path.add(start);
            if (path.size() - 1 > Math.abs(ax - bx) + Math.abs(az - bz) + 2 * radius) return null;
            int[] result = new int[path.size()];
            for (int i = 0; i < result.length; i++) result[i] = path.get(result.length - i - 1);
            return result;
        }

        private void applyDetour(int[] path, V3RoadType grade) {
            for (int i = 0; i < path.length; i++) {
                int at = path[i];
                types[at] = (byte) Math.max(types[at], grade.ordinal());
                if (i > 0) connect(path[i - 1], at);
            }
            for (int at : path) {
                int x = at % size;
                int z = at / size;
                for (Direction direction : Direction.VALUES) {
                    int nx = x + direction.dx();
                    int nz = z + direction.dz();
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                    int next = index(nx, nz);
                    if (types[next] != 0 && fact(x, z).cityLevel() == fact(nx, nz).cityLevel()) {
                        connect(at, next);
                    }
                }
            }
            detours++;
        }

        private boolean roadPath(int ax, int az, int bx, int bz) {
            int start = index(ax, az);
            int finish = index(bx, bz);
            boolean[] seen = new boolean[size * size];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int at = queue.removeFirst();
                if (at == finish) return true;
                for (Direction direction : Direction.VALUES) {
                    if ((masks[at] & direction.bit()) == 0) continue;
                    int nx = at % size + direction.dx();
                    int nz = at / size + direction.dz();
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                    int next = index(nx, nz);
                    if (!seen[next]) {
                        seen[next] = true;
                        queue.addLast(next);
                    }
                }
            }
            return false;
        }

        private void recordAxisGaps() {
            int[] components = roadComponents();
            recordVerticalGaps(0, V3RoadType.PRIMARY, components);
            for (int x : offsets(key.dx(), true)) recordVerticalGaps(x, V3RoadType.SECONDARY, components);
            recordHorizontalGaps(0, V3RoadType.PRIMARY, components);
            for (int z : offsets(key.dz(), false)) recordHorizontalGaps(z, V3RoadType.SECONDARY, components);
        }

        private void refreshAxisGapConnectivity() {
            if (axisGaps.isEmpty()) return;
            int[] components = roadComponents();
            unrepairedAxisGaps = 0;
            for (int i = 0; i < axisGaps.size(); i++) {
                V3AxisGap gap = axisGaps.get(i);
                int ax = gap.startX() - originX;
                int az = gap.startZ() - originZ;
                int bx = gap.endX() - originX;
                int bz = gap.endZ() - originZ;
                boolean vertical = ax == bx;
                int beforeX = vertical ? ax : ax - 1;
                int beforeZ = vertical ? az - 1 : az;
                int afterX = vertical ? bx : bx + 1;
                int afterZ = vertical ? bz + 1 : bz;
                boolean internal = beforeX >= 0 && beforeZ >= 0 && afterX < size && afterZ < size
                        && types[index(beforeX, beforeZ)] != 0 && types[index(afterX, afterZ)] != 0;
                boolean connected = internal
                        && components[index(beforeX, beforeZ)] == components[index(afterX, afterZ)];
                if (internal && !connected) unrepairedAxisGaps++;
                axisGaps.set(i, new V3AxisGap(gap.startX(), gap.startZ(), gap.endX(), gap.endZ(),
                        gap.grade(), gap.reason(), internal, connected));
            }
        }

        private int[] roadComponents() {
            int[] components = new int[size * size];
            Arrays.fill(components, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            int nextComponent = 0;
            for (int start = 0; start < types.length; start++) {
                if (types[start] == 0 || components[start] >= 0) continue;
                components[start] = nextComponent;
                queue.add(start);
                while (!queue.isEmpty()) {
                    int at = queue.removeFirst();
                    for (Direction direction : Direction.VALUES) {
                        if ((masks[at] & direction.bit()) == 0) continue;
                        int nx = at % size + direction.dx();
                        int nz = at / size + direction.dz();
                        if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                        int neighbor = index(nx, nz);
                        if (components[neighbor] < 0) {
                            components[neighbor] = nextComponent;
                            queue.addLast(neighbor);
                        }
                    }
                }
                nextComponent++;
            }
            return components;
        }

        private void recordVerticalGaps(int x, V3RoadType grade, int[] components) {
            for (int z = 0; z < size; ) {
                if (types[index(x, z)] != 0) {
                    z++;
                    continue;
                }
                int first = z;
                while (z < size && types[index(x, z)] == 0) z++;
                recordGap(x, first, x, z - 1, grade, components);
            }
        }

        private void recordHorizontalGaps(int z, V3RoadType grade, int[] components) {
            for (int x = 0; x < size; ) {
                if (types[index(x, z)] != 0) {
                    x++;
                    continue;
                }
                int first = x;
                while (x < size && types[index(x, z)] == 0) x++;
                recordGap(first, z, x - 1, z, grade, components);
            }
        }

        private void recordGap(int ax, int az, int bx, int bz, V3RoadType grade, int[] components) {
            boolean vertical = ax == bx;
            int beforeX = vertical ? ax : ax - 1;
            int beforeZ = vertical ? az - 1 : az;
            int afterX = vertical ? bx : bx + 1;
            int afterZ = vertical ? bz + 1 : bz;
            boolean internal = beforeX >= 0 && beforeZ >= 0 && afterX < size && afterZ < size
                    && types[index(beforeX, beforeZ)] != 0 && types[index(afterX, afterZ)] != 0;
            boolean connectedAround = internal
                    && components[index(beforeX, beforeZ)] == components[index(afterX, afterZ)];
            int level = beforeX >= 0 && beforeZ >= 0 && types[index(beforeX, beforeZ)] != 0
                    ? fact(beforeX, beforeZ).cityLevel() : fact(ax, az).cityLevel();
            V3EndReason reason = V3EndReason.UNROUTABLE;
            for (int x = ax, z = az; x <= bx && z <= bz; x += vertical ? 0 : 1, z += vertical ? 1 : 0) {
                V3EndReason blocked = blockedReason(fact(x, z), level);
                if (blocked != V3EndReason.UNROUTABLE) {
                    reason = blocked;
                    break;
                }
            }
            if (reason == V3EndReason.UNROUTABLE && internal && !connectedAround
                    && detours >= params.maxDetours()
                    && bx - ax + bz - az <= 2) {
                reason = V3EndReason.REPAIR_BUDGET;
                budgetExhausted++;
            }
            if (internal && !connectedAround) unrepairedAxisGaps++;
            axisGaps.add(new V3AxisGap(originX + ax, originZ + az,
                    originX + bx, originZ + bz, grade, reason, internal, connectedAround));
        }

        private V3EndReason blockedReason(V3Facts blocked, int level) {
            if (!blocked.land()) return blocked.city() ? V3EndReason.WATER : V3EndReason.CITY_EDGE;
            if (!blocked.city()) return V3EndReason.CITY_EDGE;
            if (blocked.cityLevel() != level) return V3EndReason.LEVEL_CHANGE;
            if (blocked.predefinedBuilding()) return V3EndReason.PREDEFINED;
            if (blocked.hardBlocked()) return V3EndReason.HARD_OBSTACLE;
            if (blocked.stationEntrance()) return V3EndReason.STATION;
            return V3EndReason.UNROUTABLE;
        }

        private void connect(int a, int b) {
            Direction direction = Direction.between(a % size, a / size, b % size, b / size);
            masks[a] |= (byte) direction.bit();
            masks[b] |= (byte) direction.opposite().bit();
        }

        private void disconnect(int a, int b) {
            Direction direction = Direction.between(a % size, a / size, b % size, b / size);
            masks[a] &= (byte) ~direction.bit();
            masks[b] &= (byte) ~direction.opposite().bit();
        }

        private void mergeOneSegment() {
            if (!Hash.chance(Hash.combine(Hash.coords(salt, key.dx(), key.dz()), MERGE_SALT),
                    params.mergeChance())) return;
            int[] xs = offsets(key.dx(), true);
            int[] zs = offsets(key.dz(), false);
            int[] xLines = withBounds(xs);
            int[] zLines = withBounds(zs);
            Merge best = null;
            for (int x : xs) {
                for (int i = 0; i < zLines.length - 1; i++) {
                    Merge candidate = mergeCandidate(x, zLines[i], x, zLines[i + 1], true);
                    if (candidate != null && (best == null || candidate.rank < best.rank)) best = candidate;
                }
            }
            for (int z : zs) {
                for (int i = 0; i < xLines.length - 1; i++) {
                    Merge candidate = mergeCandidate(xLines[i], z, xLines[i + 1], z, false);
                    if (candidate != null && (best == null || candidate.rank < best.rank)) best = candidate;
                }
            }
            if (best == null) return;
            for (int at : best.interior) {
                int x = at % size;
                int z = at / size;
                for (Direction direction : Direction.VALUES) {
                    int nx = x + direction.dx();
                    int nz = z + direction.dz();
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                    int next = index(nx, nz);
                    if ((masks[at] & direction.bit()) != 0) disconnect(at, next);
                }
                types[at] = 0;
            }
            merges++;
        }

        private int[] withBounds(int[] offsets) {
            int[] lines = new int[offsets.length + 2];
            lines[0] = 0;
            System.arraycopy(offsets, 0, lines, 1, offsets.length);
            lines[lines.length - 1] = size;
            return lines;
        }

        private Merge mergeCandidate(int ax, int az, int bx, int bz, boolean vertical) {
            // Never delete the cell immediately before the next supercell's boundary.
            if (bx >= size || bz >= size) return null;
            int length = Math.abs(bx - ax) + Math.abs(bz - az) - 1;
            if (length < 2) return null;
            int[] interior = new int[length];
            for (int i = 0; i < length; i++) {
                int x = vertical ? ax : ax + i + 1;
                int z = vertical ? az + i + 1 : az;
                int at = index(x, z);
                if (types[at] != V3RoadType.SECONDARY.ordinal()
                        || (masks[at] & 0xf) != (vertical ? Direction.mask(Direction.N, Direction.S)
                        : Direction.mask(Direction.E, Direction.W))
                        || stationWins[at]) return null;
                if (vertical) {
                    if (!flankBuildable(x - 1, z) || !flankBuildable(x + 1, z)) return null;
                } else if (!flankBuildable(x, z - 1) || !flankBuildable(x, z + 1)) {
                    return null;
                }
                interior[i] = at;
            }
            int start = index(ax, az);
            int finish = index(bx, bz);
            if (types[start] == 0 || types[finish] == 0 || !roadPathWithout(start, finish, interior)) return null;
            long rank = Hash.coords(Hash.combine(salt, MERGE_SALT), originX + ax, originZ + az);
            return new Merge(interior, rank);
        }

        private boolean flankBuildable(int x, int z) {
            return x > 0 && z > 0 && x < size && z < size
                    && types[index(x, z)] == 0 && fact(x, z).parcelPavable();
        }

        private boolean roadPathWithout(int start, int finish, int[] removed) {
            boolean[] excluded = new boolean[size * size];
            for (int at : removed) excluded[at] = true;
            boolean[] seen = new boolean[size * size];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            seen[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int at = queue.removeFirst();
                if (at == finish) return true;
                for (Direction direction : Direction.VALUES) {
                    if ((masks[at] & direction.bit()) == 0) continue;
                    int x = at % size + direction.dx();
                    int z = at / size + direction.dz();
                    if (x < 0 || z < 0 || x >= size || z >= size) continue;
                    int next = index(x, z);
                    if (!excluded[next] && !seen[next]) {
                        seen[next] = true;
                        queue.addLast(next);
                    }
                }
            }
            return false;
        }

        private void addLocalRoads() {
            int[] owner = new int[size * size];
            Arrays.fill(owner, -1);
            int faceId = 0;
            List<Face> faces = new ArrayList<>();
            List<Face> openRouteFaces = new ArrayList<>();
            for (int z = 1; z < size; z++) {
                for (int x = 1; x < size; x++) {
                    int start = index(x, z);
                    if (owner[start] >= 0 || types[start] != 0 || !fact(x, z).parcelPavable()) continue;
                    Face face = collectFace(start, faceId++, owner);
                    if (!face.closed) {
                        openFaces++;
                        openRouteFaces.add(face);
                        continue;
                    }
                    closedFaces++;
                    minClosedFaceArea = Math.min(minClosedFaceArea, face.cells.size());
                    maxClosedFaceArea = Math.max(maxClosedFaceArea, face.cells.size());
                    depth(face, owner);
                    faces.add(face);
                }
            }
            Face[] byId = new Face[faceId];
            Local[] baseline = new Local[faceId];
            for (Face face : faces) {
                byId[face.id] = face;
                baseline[face.id] = chooseLocal(face, owner);
            }
            boolean[] used = new boolean[faceId];
            List<Face> placed = new ArrayList<>();
            for (Local cross : crossRoutes(byId, baseline, owner)) {
                if (used[cross.faceA] || used[cross.faceB] || !routeAvailable(cross)) continue;
                applyLocal(cross);
                throughRoads++;
                crossRoads++;
                used[cross.faceA] = true;
                used[cross.faceB] = true;
                placed.add(byId[cross.faceA]);
                placed.add(byId[cross.faceB]);
            }
            for (Face face : faces) {
                if (used[face.id]) continue;
                Local local = baseline[face.id];
                if (local != null && !routeAvailable(local)) local = chooseLocal(face, owner);
                if (local == null || !routeAvailable(local)) {
                    if (face.maxDepth >= params.spurDepth()) noLocalRouteFaces++;
                    continue;
                }
                applyLocal(local);
                if (local.through) throughRoads++;
                else spurRoads++;
                placed.add(face);
            }
            for (Face face : placed) faceId = addExtraLocals(face, owner, faceId);
            for (Face face : openRouteFaces) {
                depth(face, owner);
                if (face.maxDepth < params.throughDepth()) continue;
                Local local = bestThrough(face, owner);
                boolean available = local != null && routeAvailable(local);
                if (!available) {
                    local = bestBent(face, owner);
                    available = local != null && routeAvailable(local);
                }
                if (available) {
                    applyLocal(local);
                    throughRoads++;
                }
            }
        }

        private Local chooseLocal(Face face, int[] owner) {
            Local local = face.maxDepth >= params.throughDepth() ? bestThrough(face, owner) : null;
            if (local == null && face.maxDepth >= params.throughDepth()) local = bestBent(face, owner);
            if (local == null && face.maxDepth >= params.spurDepth()) local = bestSpur(face, owner);
            return local;
        }

        private List<Local> crossRoutes(Face[] byId, Local[] baseline, int[] owner) {
            List<Crossing> crossings = new ArrayList<>();
            for (int z = 1; z < size - 1; z++) {
                for (int x = 1; x < size - 1; x++) {
                    int host = index(x, z);
                    if (types[host] < V3RoadType.SECONDARY.ordinal()) continue;
                    for (Direction across : new Direction[] {Direction.N, Direction.E}) {
                        Direction left = across.clockwise();
                        Direction right = across.counterClockwise();
                        if ((masks[host] & (left.bit() | right.bit())) != (left.bit() | right.bit())
                                || (masks[host] & (across.bit() | across.opposite().bit())) != 0) continue;
                        int firstA = index(x + across.dx(), z + across.dz());
                        int firstB = index(x - across.dx(), z - across.dz());
                        int idA = owner[firstA];
                        int idB = owner[firstB];
                        if (idA < 0 || idB < 0 || idA == idB || byId[idA] == null || byId[idB] == null
                                || byId[idA].maxDepth < params.throughDepth()
                                || byId[idB].maxDepth < params.throughDepth()
                                || protectedParcel[firstA] || protectedParcel[firstB]
                                || fact(x, z).cityLevel() != fact(x + across.dx(), z + across.dz()).cityLevel()
                                || fact(x, z).cityLevel() != fact(x - across.dx(), z - across.dz()).cityLevel()) {
                            continue;
                        }
                        crossings.add(new Crossing(host, firstA, firstB, idA, idB, across));
                    }
                }
            }
            crossings.sort(Comparator.comparingInt((Crossing c) -> Math.min(c.faceA, c.faceB))
                    .thenComparingInt(c -> Math.max(c.faceA, c.faceB))
                    .thenComparingInt(c -> distanceTo(c.host, byId[c.faceA].deepest)
                            + distanceTo(c.host, byId[c.faceB].deepest))
                    .thenComparingInt(c -> c.host));
            Map<Long, Integer> tried = new HashMap<>();
            List<CrossRoute> routes = new ArrayList<>();
            for (Crossing crossing : crossings) {
                int low = Math.min(crossing.faceA, crossing.faceB);
                int high = Math.max(crossing.faceA, crossing.faceB);
                long pair = ((long) low << 32) | high;
                int count = tried.getOrDefault(pair, 0);
                if (count >= MAX_CROSS_HOSTS_PER_PAIR) continue;
                tried.put(pair, count + 1);
                Face a = byId[crossing.faceA];
                Face b = byId[crossing.faceB];
                Local legA = bestLeg(a, owner, new Port(crossing.host, crossing.firstA,
                        crossing.towardA), baseline[a.id]);
                Local legB = bestLeg(b, owner, new Port(crossing.host, crossing.firstB,
                        crossing.towardA.opposite()), baseline[b.id]);
                if (legA == null || legB == null || legA.bends + legB.bends > 2) continue;
                int residualA = baseline[a.id] == null ? a.maxDepth : baseline[a.id].residualDepth;
                int residualB = baseline[b.id] == null ? b.maxDepth : baseline[b.id].residualDepth;
                if (legA.residualDepth > residualA || legB.residualDepth > residualB) continue;
                Local route = joinLegs(legA, legB, crossing.host);
                routes.add(new CrossRoute(route, localAreaScore(route.cells, params)));
            }
            routes.sort((a, b) -> {
                int score = compareAreaScore(a.score, b.score);
                if (score != 0) return score;
                Local ar = a.route;
                Local br = b.route;
                int aGain = Math.min(referenceDepth(byId[ar.faceA], baseline[ar.faceA]) - ar.residualDepth,
                        referenceDepth(byId[ar.faceB], baseline[ar.faceB]) - ar.otherResidualDepth);
                int bGain = Math.min(referenceDepth(byId[br.faceA], baseline[br.faceA]) - br.residualDepth,
                        referenceDepth(byId[br.faceB], baseline[br.faceB]) - br.otherResidualDepth);
                if (aGain != bGain) return Integer.compare(bGain, aGain);
                if (ar.bends != br.bends) return Integer.compare(ar.bends, br.bends);
                return Long.compareUnsigned(ar.rank, br.rank);
            });
            for (int i = 1; i < routes.size(); i++) {
                if (routes.get(i - 1).score.equals(routes.get(i).score)) crossScoreTies++;
            }
            return routes.stream().map(CrossRoute::route).toList();
        }

        private int compareAreaScore(AreaScore a, AreaScore b) {
            if (a.effectiveAreas != b.effectiveAreas) {
                return Integer.compare(b.effectiveAreas, a.effectiveAreas);
            }
            if (a.minimumCells != b.minimumCells) {
                return Integer.compare(b.minimumCells, a.minimumCells);
            }
            return Integer.compare(b.cappedCells, a.cappedCells);
        }

        private int referenceDepth(Face face, Local baseline) {
            return baseline == null ? face.maxDepth : baseline.residualDepth;
        }

        private int distanceTo(int a, int b) {
            return Math.abs(a % size - b % size) + Math.abs(a / size - b / size);
        }

        private Local bestLeg(Face face, int[] owner, Port crossing, Local baseline) {
            Local best = null;
            if (baseline != null && baseline.through) {
                int[] path = baseline.path;
                if (path[path.length - 1] == crossing.host
                        && path[path.length - 2] == crossing.first) best = baseline;
                if (path[0] == crossing.host && path[1] == crossing.first) best = reversed(baseline);
            }
            int checked = 0;
            for (int pocket : deepPockets(face)) {
                List<Port> ports = new ArrayList<>();
                for (List<Port> side : portsNear(face, owner, pocket)) ports.addAll(side);
                ports.sort(Comparator.comparingInt((Port port) ->
                                port.inward == crossing.inward.opposite() ? 0 : 1)
                        .thenComparingInt(port -> distanceTo(port.first, pocket))
                        .thenComparingInt(port -> port.host));
                for (Port outer : ports) {
                    if (outer.host == crossing.host || outer.inward == crossing.inward) continue;
                    for (int shape = 0; shape < 2; shape++) {
                        if (++checked > MAX_CROSS_LEG_TEMPLATES) return best;
                        localTemplateChecks++;
                        int maxCells = baseline != null
                                ? baseline.cells.length + MAX_CROSS_EXTRA_CELLS : Integer.MAX_VALUE;
                        Local leg = bentAt(face, owner, outer, crossing, pocket, shape, maxCells);
                        if (leg != null && leg.bends <= 1) best = betterCrossLeg(best, leg);
                    }
                }
            }
            return best;
        }

        private Local betterCrossLeg(Local current, Local candidate) {
            if (current == null) return candidate;
            if (candidate.residualDepth != current.residualDepth) {
                return candidate.residualDepth < current.residualDepth ? candidate : current;
            }
            int areaOrder = compareAreaScore(localAreaScore(candidate.cells, params),
                    localAreaScore(current.cells, params));
            if (areaOrder != 0) return areaOrder < 0 ? candidate : current;
            if (candidate.bends != current.bends) return candidate.bends < current.bends ? candidate : current;
            if (candidate.cells.length != current.cells.length) {
                return candidate.cells.length > current.cells.length ? candidate : current;
            }
            return Long.compareUnsigned(candidate.rank, current.rank) < 0 ? candidate : current;
        }

        private Local reversed(Local local) {
            int[] path = local.path.clone();
            int[] cells = local.cells.clone();
            for (int a = 0, b = path.length - 1; a < b; a++, b--) {
                int swap = path[a];
                path[a] = path[b];
                path[b] = swap;
            }
            for (int a = 0, b = cells.length - 1; a < b; a++, b--) {
                int swap = cells[a];
                cells[a] = cells[b];
                cells[b] = swap;
            }
            return new Local(cells, path, local.faceA, local.faceB, local.through,
                    local.residualDepth, local.otherResidualDepth, local.rank, local.bends);
        }

        private Local joinLegs(Local a, Local b, int host) {
            if (a.path[a.path.length - 1] != host || b.path[b.path.length - 1] != host) {
                throw new IllegalStateException("cross-face legs do not meet at their host");
            }
            int[] path = Arrays.copyOf(a.path, a.path.length + b.path.length - 1);
            for (int i = b.path.length - 2, out = a.path.length; i >= 0; i--, out++) {
                path[out] = b.path[i];
            }
            int[] cells = Arrays.copyOf(a.cells, a.cells.length + b.cells.length);
            for (int i = b.cells.length - 1, out = a.cells.length; i >= 0; i--, out++) {
                cells[out] = b.cells[i];
            }
            return new Local(cells, path, a.faceA, b.faceA, true, a.residualDepth, b.residualDepth,
                    Hash.combine(localRank(host), Hash.combine(a.rank, b.rank)), a.bends + b.bends);
        }

        private boolean routeAvailable(Local local) {
            boolean[] inPath = new boolean[size * size];
            for (int at : local.path) {
                if (inPath[at]) return false;
                inPath[at] = true;
            }
            for (int at : local.cells) {
                if (types[at] != 0 || protectedParcel[at]) return false;
                int x = at % size;
                int z = at / size;
                for (Direction direction : Direction.VALUES) {
                    int nx = x + direction.dx();
                    int nz = z + direction.dz();
                    if (inside(nx, nz) && types[index(nx, nz)] == V3RoadType.TERTIARY.ordinal()
                            && !inPath[index(nx, nz)]) return false;
                }
            }
            for (int i = 1; i < local.path.length; i++) {
                int a = local.path[i - 1];
                int b = local.path[i];
                Direction direction = Direction.between(a % size, a / size, b % size, b / size);
                if ((masks[a] & direction.bit()) != 0) return false;
            }
            return true;
        }

        /**
         * Decision D10/D13: give a block more than one local road when there is still room.
         *
         * <p>After the first local road of a face, the cells that face still owns are
         * re-partitioned — the new road and the protected parcels act as boundaries — and every
         * sub-face that is still deep enough may take another local road, up to
         * {@link V3Params#localsPerFace()} along each recursive branch. The alternative considered
         * and rejected was lowering the depth threshold: that would also push roads into the
         * small parcels that are deliberately left alone.
         *
         * <p>Sub-faces are collected fresh instead of being derived from the parent's distance
         * field, so the choice logic stays exactly the one a face of that shape would get if it
         * had been collected first. Counters for "the areas before local roads" are deliberately
         * untouched: the contract names those numbers, and only the applied-road counters grow.
         *
         * @return the next free face id
         */
        private int addExtraLocals(Face parent, int[] owner, int faceId) {
            if (params.localsPerFace() <= 1) {
                return faceId;
            }
            return addExtraLocals(parent, owner, faceId, 1);
        }

        private int addExtraLocals(Face parent, int[] owner, int faceId, int placed) {
            if (placed >= params.localsPerFace()) {
                return faceId;
            }
            List<Integer> remaining = new ArrayList<>();
            for (int at : parent.cells) {
                if (types[at] == 0 && !protectedParcel[at]) {
                    owner[at] = -1;
                    remaining.add(at);
                }
            }
            if (remaining.isEmpty()) {
                return faceId;
            }
            List<Face> subFaces = new ArrayList<>();
            for (int at : remaining) {
                if (owner[at] >= 0) continue;
                Face sub = collectFace(at, faceId++, owner);
                if (sub.closed) {
                    subFaces.add(sub);
                } else {
                    openFaces++;
                }
            }
            for (Face sub : subFaces) {
                depth(sub, owner);
                if (sub.maxDepth < params.extraLocalDepth()) {
                    continue;
                }
                Local local = sub.maxDepth >= params.throughDepth() ? bestThrough(sub, owner) : null;
                if (local == null && sub.maxDepth >= params.throughDepth()) {
                    local = bestBent(sub, owner);
                }
                if (local == null && sub.maxDepth >= params.spurDepth()) {
                    local = bestSpur(sub, owner);
                }
                if (local == null || !routeAvailable(local)) {
                    if (sub.maxDepth >= params.spurDepth()) {
                        noLocalRouteFaces++;
                    }
                    continue;
                }
                applyLocal(local);
                if (local.through) throughRoads++;
                else spurRoads++;
                faceId = addExtraLocals(sub, owner, faceId, placed + 1);
            }
            return faceId;
        }

        private Face collectFace(int start, int id, int[] owner) {
            Face face = new Face(id, size);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            owner[start] = id;
            queue.add(start);
            while (!queue.isEmpty()) {
                int at = queue.removeFirst();
                int x = at % size;
                int z = at / size;
                face.cells.add(at);
                face.minX = Math.min(face.minX, x);
                face.maxX = Math.max(face.maxX, x);
                face.minZ = Math.min(face.minZ, z);
                face.maxZ = Math.max(face.maxZ, z);
                for (Direction direction : Direction.VALUES) {
                    int nx = x + direction.dx();
                    int nz = z + direction.dz();
                    if (nx <= 0 || nz <= 0 || nx >= size || nz >= size) {
                        if (!roadAt(nx, nz).isRoad() || fact(nx, nz).cityLevel() != fact(x, z).cityLevel()) {
                            face.closed = false;
                        }
                        continue;
                    }
                    int next = index(nx, nz);
                    if (types[next] != 0) {
                        if (fact(nx, nz).cityLevel() != fact(x, z).cityLevel()) face.closed = false;
                    } else if (!fact(nx, nz).parcelPavable()) {
                        face.closed = false;
                    } else if (owner[next] < 0) {
                        owner[next] = id;
                        queue.addLast(next);
                    }
                }
            }
            return face;
        }

        private V3RoadType roadAt(int x, int z) {
            if (x >= 0 && z >= 0 && x < size && z < size) return TYPES[types[index(x, z)] & 0xff];
            if (x >= -1 && z >= -1 && x <= size && z <= size) return candidateType(x, z);
            return V3RoadType.NONE;
        }

        private void depth(Face face, int[] owner) {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int at : face.cells) {
                int x = at % size;
                int z = at / size;
                for (Direction direction : Direction.VALUES) {
                    if (roadAt(x + direction.dx(), z + direction.dz()).isRoad()
                            && fact(x + direction.dx(), z + direction.dz()).cityLevel()
                            == fact(x, z).cityLevel()) {
                        face.distance[at] = 1;
                        queue.addLast(at);
                        break;
                    }
                }
            }
            while (!queue.isEmpty()) {
                int at = queue.removeFirst();
                for (Direction direction : Direction.VALUES) {
                    int x = at % size + direction.dx();
                    int z = at / size + direction.dz();
                    if (x < 0 || z < 0 || x >= size || z >= size) continue;
                    int next = index(x, z);
                    if (owner[next] == face.id
                            && fact(x, z).cityLevel() == fact(at % size, at / size).cityLevel()
                            && face.distance[next] > face.distance[at] + 1) {
                        face.distance[next] = face.distance[at] + 1;
                        queue.addLast(next);
                    }
                }
            }
            for (int at : face.cells) {
                if (face.distance[at] != Integer.MAX_VALUE && face.distance[at] > face.maxDepth) {
                    face.maxDepth = face.distance[at];
                    face.deepest = at;
                }
            }
        }

        private Local bestThrough(Face face, int[] owner) {
            Local best = null;
            int deepX = face.deepest % size;
            int deepZ = face.deepest / size;
            for (int i = 0; i < params.localCandidates(); i++) {
                int delta = (i + 1) / 2 * (i % 2 == 0 ? -1 : 1);
                Local vertical = throughAt(face, owner, deepX + delta, true);
                Local horizontal = throughAt(face, owner, deepZ + delta, false);
                best = better(best, vertical);
                best = better(best, horizontal);
            }
            return best;
        }

        private Local bestBent(Face face, int[] owner) {
            Local best = null;
            int checked = 0;
            for (int pocket : deepPockets(face)) {
                List<List<Port>> sides = portsNear(face, owner, pocket);
                for (int a = 0; a < Direction.COUNT; a++) {
                    for (int b = a + 1; b < Direction.COUNT; b++) {
                        for (Port from : sides.get(a)) {
                            for (Port to : sides.get(b)) {
                                for (int shape = 0; shape < 2; shape++) {
                                    if (++checked > MAX_LOCAL_TEMPLATES) return best;
                                    localTemplateChecks++;
                                    best = better(best, bentAt(face, owner, from, to, pocket, shape));
                                }
                            }
                        }
                    }
                }
            }
            return best;
        }

        private List<Integer> deepPockets(Face face) {
            List<Integer> pockets = new ArrayList<>(MAX_LOCAL_POCKETS);
            int separation = Math.max(3, Math.min(face.maxX - face.minX, face.maxZ - face.minZ) / 4);
            for (int slot = 0; slot < MAX_LOCAL_POCKETS; slot++) {
                int best = -1;
                for (int at : face.cells) {
                    if (protectedParcel[at] || face.distance[at] == Integer.MAX_VALUE) continue;
                    boolean separate = true;
                    for (int earlier : pockets) {
                        if (Math.abs(at % size - earlier % size) + Math.abs(at / size - earlier / size)
                                < separation) {
                            separate = false;
                            break;
                        }
                    }
                    if (separate && (best < 0 || face.distance[at] > face.distance[best]
                            || (face.distance[at] == face.distance[best]
                            && Long.compareUnsigned(localRank(at), localRank(best)) < 0))) best = at;
                }
                if (best < 0 || face.distance[best] < params.throughDepth()) break;
                pockets.add(best);
            }
            return pockets;
        }

        private List<List<Port>> portsNear(Face face, int[] owner, int pocket) {
            List<List<Port>> sides = new ArrayList<>(Direction.COUNT);
            for (Direction ignored : Direction.VALUES) sides.add(new ArrayList<>());
            for (int at : face.cells) {
                if (types[at] != 0 || protectedParcel[at]) continue;
                int x = at % size;
                int z = at / size;
                for (Direction side : Direction.VALUES) {
                    int hx = x + side.dx();
                    int hz = z + side.dz();
                    if (!parentRoad(hx, hz) || fact(hx, hz).cityLevel() != fact(x, z).cityLevel()) continue;
                    int host = index(hx, hz);
                    Direction inward = side.opposite();
                    if (masks[host] == 0 || (masks[host] & inward.bit()) != 0) continue;
                    int nextX = x + inward.dx();
                    int nextZ = z + inward.dz();
                    if (x <= 0 || z <= 0 || x >= size - 1 || z >= size - 1
                            || !inside(nextX, nextZ) || owner[index(nextX, nextZ)] != face.id
                            || types[index(nextX, nextZ)] != 0 || protectedParcel[index(nextX, nextZ)]
                            || fact(nextX, nextZ).cityLevel() != fact(x, z).cityLevel()) continue;
                    sides.get(inward.ordinal()).add(new Port(host, at, inward));
                }
            }
            for (List<Port> ports : sides) {
                ports.sort(Comparator.comparingInt((Port port) ->
                                Math.abs(port.first % size - pocket % size)
                                        + Math.abs(port.first / size - pocket / size))
                        .thenComparingInt(port -> port.host).thenComparingInt(port -> port.first));
                if (ports.size() > MAX_LOCAL_PORTS_PER_SIDE) {
                    ports.subList(MAX_LOCAL_PORTS_PER_SIDE, ports.size()).clear();
                }
            }
            return sides;
        }

        private Local bentAt(Face face, int[] owner, Port from, Port to, int pocket, int shape) {
            return bentAt(face, owner, from, to, pocket, shape, Integer.MAX_VALUE);
        }

        private Local bentAt(Face face, int[] owner, Port from, Port to, int pocket, int shape,
                             int maxCells) {
            if (from.host == to.host) return null;
            int ax = from.first % size + from.inward.dx();
            int az = from.first / size + from.inward.dz();
            int bx = to.first % size + to.inward.dx();
            int bz = to.first / size + to.inward.dz();
            if (!inside(ax, az) || !inside(bx, bz)) return null;
            List<Integer> path = new ArrayList<>();
            path.add(from.host);
            path.add(from.first);
            path.add(index(ax, az));
            boolean opposite = from.inward.opposite() == to.inward;
            if (opposite) {
                if (shape == 0) {
                    if (!appendLine(path, bx, bz)) return null;
                } else if (from.inward.dx() != 0) {
                    int mid = pocket % size;
                    if (!appendLine(path, mid, az) || !appendLine(path, mid, bz)
                            || !appendLine(path, bx, bz)) return null;
                } else {
                    int mid = pocket / size;
                    if (!appendLine(path, ax, mid) || !appendLine(path, bx, mid)
                            || !appendLine(path, bx, bz)) return null;
                }
            } else if (shape == 0) {
                if (!appendLine(path, bx, az) || !appendLine(path, bx, bz)) return null;
            } else {
                if (!appendLine(path, ax, bz) || !appendLine(path, bx, bz)) return null;
            }
            if (!appendLine(path, to.first % size, to.first / size)
                    || !appendLine(path, to.host % size, to.host / size)) return null;
            if (path.size() - 2 > maxCells) return null;
            return checkedRoute(face, owner, path);
        }

        private boolean appendLine(List<Integer> path, int x, int z) {
            if (!inside(x, z)) return false;
            int at = path.get(path.size() - 1);
            int dx = Integer.compare(x, at % size);
            int dz = Integer.compare(z, at / size);
            if (dx != 0 && dz != 0) return false;
            int px = at % size;
            int pz = at / size;
            while (px != x || pz != z) {
                px += dx;
                pz += dz;
                path.add(index(px, pz));
            }
            return true;
        }

        private boolean inside(int x, int z) {
            return x >= 0 && z >= 0 && x < size && z < size;
        }

        private Local checkedRoute(Face face, int[] owner, List<Integer> path) {
            if (path.size() < 4) return null;
            int start = path.get(0);
            int end = path.get(path.size() - 1);
            if (!parentRoad(start % size, start / size) || !parentRoad(end % size, end / size)
                    || fact(start % size, start / size).cityLevel()
                    != fact(end % size, end / size).cityLevel()) return null;
            boolean[] inPath = new boolean[size * size];
            for (int at : path) {
                if (inPath[at]) return null;
                inPath[at] = true;
            }
            Direction previous = null;
            int run = 0;
            int bends = 0;
            for (int i = 1; i < path.size(); i++) {
                int a = path.get(i - 1);
                int b = path.get(i);
                if (!Direction.adjacent(a % size, a / size, b % size, b / size)) return null;
                Direction direction = Direction.between(a % size, a / size, b % size, b / size);
                if (previous != direction) {
                    if (previous != null && run < 2) return null;
                    if (previous != null) bends++;
                    run = 0;
                }
                run++;
                previous = direction;
            }
            if (run < 2 || bends > 2) return null;
            int level = fact(start % size, start / size).cityLevel();
            int[] cells = new int[path.size() - 2];
            for (int i = 1; i < path.size() - 1; i++) {
                int at = path.get(i);
                int x = at % size;
                int z = at / size;
                if (x <= 0 || z <= 0 || x >= size - 1 || z >= size - 1
                        || owner[at] != face.id || types[at] != 0 || protectedParcel[at]
                        || fact(x, z).cityLevel() != level) return null;
                boolean flank = false;
                for (Direction side : Direction.VALUES) {
                    int nx = x + side.dx();
                    int nz = z + side.dz();
                    if (!inside(nx, nz)) continue;
                    int next = index(nx, nz);
                    if (next == path.get(i - 1) || next == path.get(i + 1)) continue;
                    if (inPath[next] || types[next] != 0) return null;
                    if (owner[next] == face.id && !protectedParcel[next]) flank = true;
                }
                if (!flank) return null;
                cells[i - 1] = at;
            }
            int[] sequence = path.stream().mapToInt(Integer::intValue).toArray();
            return new Local(cells, sequence, face.id, -1, true, residualDepth(face, owner, cells), -1,
                    Hash.combine(localRank(cells[0]), localRank(cells[cells.length - 1])), bends);
        }

        private Local throughAt(Face face, int[] owner, int line, boolean vertical) {
            int lo = vertical ? face.minZ : face.minX;
            int hi = vertical ? face.maxZ : face.maxX;
            if (line <= 1 || line >= size - 1 || hi - lo + 1 < 3) return null;
            int ax = vertical ? line : lo - 1;
            int az = vertical ? lo - 1 : line;
            int bx = vertical ? line : hi + 1;
            int bz = vertical ? hi + 1 : line;
            if (!parentRoad(ax, az) || !parentRoad(bx, bz)
                    || fact(ax, az).cityLevel() != fact(bx, bz).cityLevel()) return null;
            int[] cells = new int[hi - lo + 1];
            for (int p = lo; p <= hi; p++) {
                int x = vertical ? line : p;
                int z = vertical ? p : line;
                int at = index(x, z);
                // Every cell of the through line must sit on the same level as its two parent
                // roads. A face is allowed to contain a level change (`collectFace` only rejects
                // one whose differing neighbour is a road or an obstacle, so a differing but
                // pavable cell silently joins it), and a line crossing that change would publish
                // a "flat" local road with a 6-block step in the middle. `spurAt` and
                // `findDetour` already check this; the through line is the only path that did not.
                if (owner[at] != face.id || protectedParcel[at]
                        || fact(x, z).cityLevel() != fact(ax, az).cityLevel()) return null;
                for (int side : new int[] {-1, 1}) {
                    int flankX = vertical ? x + side : x;
                    int flankZ = vertical ? z : z + side;
                    if (flankX <= 0 || flankZ <= 0 || flankX >= size || flankZ >= size
                            || owner[index(flankX, flankZ)] != face.id) return null;
                }
                cells[p - lo] = at;
            }
            return localRoute(cells, index(ax, az), index(bx, bz), face.id, true,
                    residualDepth(face, owner, cells));
        }

        private Local bestSpur(Face face, int[] owner) {
            Local best = null;
            int deepX = face.deepest % size;
            int deepZ = face.deepest / size;
            for (Direction direction : Direction.VALUES) {
                for (int i = 0; i < params.localCandidates(); i++) {
                    int delta = (i + 1) / 2 * (i % 2 == 0 ? -1 : 1);
                    int rootX = switch (direction) {
                        case E -> face.minX - 1;
                        case W -> face.maxX + 1;
                        default -> deepX + delta;
                    };
                    int rootZ = switch (direction) {
                        case S -> face.minZ - 1;
                        case N -> face.maxZ + 1;
                        default -> deepZ + delta;
                    };
                    best = better(best, spurAt(face, owner, rootX, rootZ, direction));
                }
            }
            return best;
        }

        private Local spurAt(Face face, int[] owner, int rootX, int rootZ, Direction direction) {
            if (!parentRoad(rootX, rootZ)) return null;
            int max = params.maxSpurLength();
            List<Integer> cells = new ArrayList<>(max);
            for (int step = 1; step <= max; step++) {
                int x = rootX + step * direction.dx();
                int z = rootZ + step * direction.dz();
                if (x <= 0 || z <= 0 || x >= size || z >= size) break;
                int at = index(x, z);
                if (owner[at] != face.id || protectedParcel[at]
                        || fact(x, z).cityLevel() != fact(rootX, rootZ).cityLevel()) break;
                boolean sideBuildable = false;
                boolean touchesOtherRoad = false;
                for (Direction side : Direction.VALUES) {
                    if (side == direction || side == direction.opposite()) continue;
                    int nx = x + side.dx();
                    int nz = z + side.dz();
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                    if (owner[index(nx, nz)] == face.id) sideBuildable = true;
                    if (roadAt(nx, nz).isRoad()) touchesOtherRoad = true;
                }
                if (!sideBuildable || touchesOtherRoad) break;
                cells.add(at);
            }
            if (cells.size() < 2) return null;
            Local best = null;
            for (int length = 2; length <= cells.size(); length++) {
                int[] prefix = cells.subList(0, length).stream().mapToInt(Integer::intValue).toArray();
                int tip = prefix[length - 1];
                int beyondX = tip % size + direction.dx();
                int beyondZ = tip / size + direction.dz();
                if (roadAt(beyondX, beyondZ).isRoad()) continue;
                best = better(best, localRoute(prefix, index(rootX, rootZ), -1, face.id, false,
                        residualDepth(face, owner, prefix)));
            }
            return best;
        }

        private boolean parentRoad(int x, int z) {
            return x >= 0 && z >= 0 && x < size && z < size
                    && types[index(x, z)] >= V3RoadType.SECONDARY.ordinal();
        }

        private int residualDepth(Face face, int[] owner, int[] newRoad) {
            localResidualChecks++;
            int[] distance = face.distance.clone();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int at : newRoad) {
                distance[at] = 0;
                queue.addLast(at);
            }
            while (!queue.isEmpty()) {
                int at = queue.removeFirst();
                for (Direction direction : Direction.VALUES) {
                    int x = at % size + direction.dx();
                    int z = at / size + direction.dz();
                    if (x < 0 || z < 0 || x >= size || z >= size) continue;
                    int next = index(x, z);
                    if (owner[next] == face.id
                            && fact(x, z).cityLevel() == fact(at % size, at / size).cityLevel()
                            && distance[next] > distance[at] + 1) {
                        distance[next] = distance[at] + 1;
                        queue.addLast(next);
                    }
                }
            }
            int max = 0;
            for (int at : face.cells) {
                if (distance[at] != Integer.MAX_VALUE) max = Math.max(max, distance[at]);
            }
            return max;
        }

        private long localRank(int at) {
            return Hash.coords(salt, originX + at % size, originZ + at / size);
        }

        private Local localRoute(int[] cells, int start, int end, int faceId, boolean through,
                                 int residual) {
            int[] path = new int[cells.length + (through ? 2 : 1)];
            path[0] = start;
            System.arraycopy(cells, 0, path, 1, cells.length);
            if (through) path[path.length - 1] = end;
            return new Local(cells, path, faceId, -1, through, residual, -1,
                    localRank(cells[0]), 0);
        }

        private Local better(Local current, Local candidate) {
            if (candidate == null) return current;
            if (current == null) return candidate;
            if (candidate.residualDepth != current.residualDepth) {
                return candidate.residualDepth < current.residualDepth ? candidate : current;
            }
            if (candidate.bends != current.bends) return candidate.bends < current.bends ? candidate : current;
            if (candidate.cells.length != current.cells.length) {
                return candidate.cells.length < current.cells.length ? candidate : current;
            }
            return Long.compareUnsigned(candidate.rank, current.rank) < 0 ? candidate : current;
        }

        private void applyLocal(Local local) {
            for (int at : local.cells) {
                if (types[at] != 0) throw new IllegalStateException("local route overlaps a road");
                types[at] = (byte) V3RoadType.TERTIARY.ordinal();
            }
            for (int i = 1; i < local.path.length; i++) {
                int a = local.path[i - 1];
                int b = local.path[i];
                Direction direction = Direction.between(a % size, a / size, b % size, b / size);
                if ((masks[a] & direction.bit()) != 0) {
                    throw new IllegalStateException("local route overlaps an existing edge");
                }
                connect(a, b);
            }
            appliedLocals.add(local);
            if (local.bends > 0) bentRoads++;
        }

        private void preserveRareParcel() {
            List<V3Footprint> guaranteed = new ArrayList<>();
            for (V3Footprint footprint : footprints) {
                if (footprint.area() < 16) break;
                boolean covered = false;
                for (V3Footprint earlier : guaranteed) {
                    if (earlier.x() >= footprint.x() && earlier.z() >= footprint.z()) {
                        covered = true;
                        break;
                    }
                }
                if (covered) continue;
                ParcelOption best = null;
                int bestCost = Integer.MAX_VALUE;
                long bestRank = 0;
                boolean alreadyFree = false;
                for (int az = 0; az < size; az += params.areaSize()) {
                    for (int ax = 0; ax < size; ax += params.areaSize()) {
                        for (int z = az; z <= az + params.areaSize() - footprint.z(); z++) {
                            for (int x = ax; x <= ax + params.areaSize() - footprint.x(); x++) {
                                if (rectangleFits(x, z, footprint, true)) {
                                    alreadyFree = true;
                                    break;
                                }
                                if (!rectangleFitsWithoutLocals(x, z, footprint)) continue;
                                ParcelOption option = new ParcelOption(x, z, footprint);
                                int cost = 0;
                                for (Local local : appliedLocals) {
                                    if (intersects(local, option)) cost += local.cells.length;
                                }
                                long rank = Hash.coords(Hash.combine(salt, footprint.area()),
                                        originX + x, originZ + z);
                                if (best == null || cost < bestCost
                                        || (cost == bestCost && Long.compareUnsigned(rank, bestRank) < 0)) {
                                    best = option;
                                    bestCost = cost;
                                    bestRank = rank;
                                }
                            }
                            if (alreadyFree) break;
                        }
                        if (alreadyFree) break;
                    }
                    if (alreadyFree) break;
                }
                if (alreadyFree) {
                    guaranteed.add(footprint);
                    continue;
                }
                if (best != null) {
                    for (Local local : List.copyOf(appliedLocals)) {
                        if (intersects(local, best)) removeLocal(local);
                    }
                    if (!rectangleFits(best.x, best.z, footprint, true)) {
                        throw new IllegalStateException("rare parcel rescue did not clear its footprint");
                    }
                    rareRescues++;
                    guaranteed.add(footprint);
                    continue;
                }
                classifyRareParcelFailure(footprint);
            }
        }

        private void classifyRareParcelFailure(V3Footprint footprint) {
            boolean raw = false;
            boolean axisFree = false;
            for (int az = 0; az < size; az += params.areaSize()) {
                for (int ax = 0; ax < size; ax += params.areaSize()) {
                    for (int z = az; z <= az + params.areaSize() - footprint.z(); z++) {
                        for (int x = ax; x <= ax + params.areaSize() - footprint.x(); x++) {
                            if (!rectangleFits(x, z, footprint, false)) continue;
                            raw = true;
                            boolean clear = true;
                            for (int dz = 0; dz < footprint.z() && clear; dz++) {
                                for (int dx = 0; dx < footprint.x(); dx++) {
                                    if (axisTypeAt(originX + x + dx, originZ + z + dz).isRoad()) {
                                        clear = false;
                                        break;
                                    }
                                }
                            }
                            if (clear) axisFree = true;
                        }
                    }
                }
            }
            if (!raw) rareNoRawCandidate++;
            else if (!axisFree) rareAxisBlocked++;
            else rareSpineBlocked++;
        }

        private boolean rectangleFitsWithoutLocals(int x, int z, V3Footprint footprint) {
            int level = fact(x, z).cityLevel();
            for (int dz = 0; dz < footprint.z(); dz++) {
                for (int dx = 0; dx < footprint.x(); dx++) {
                    int px = x + dx;
                    int pz = z + dz;
                    V3Facts f = fact(px, pz);
                    if (!f.parcelPavable() || f.cityLevel() != level
                            || types[index(px, pz)] > V3RoadType.TERTIARY.ordinal()) return false;
                }
            }
            return true;
        }

        private boolean intersects(Local local, ParcelOption option) {
            for (int at : local.cells) {
                int x = at % size;
                int z = at / size;
                if (x >= option.x && x < option.x + option.footprint.x()
                        && z >= option.z && z < option.z + option.footprint.z()) return true;
            }
            return false;
        }

        private void removeLocal(Local local) {
            for (int i = 1; i < local.path.length; i++) {
                disconnect(local.path[i - 1], local.path[i]);
            }
            for (int at : local.cells) {
                if (masks[at] != 0) throw new IllegalStateException("local route shares a cell");
                types[at] = 0;
            }
            appliedLocals.remove(local);
            if (local.through) throughRoads--;
            else spurRoads--;
            if (local.faceB >= 0) crossRoads--;
            if (local.bends > 0) bentRoads--;
            rareSuppressedLocals++;
            rareSuppressedCells += local.cells.length;
        }

        private void dropIsolatedCells() {
            for (int i = 0; i < types.length; i++) {
                if (types[i] != 0 && masks[i] == 0) {
                    types[i] = 0;
                    droppedIsolated++;
                }
            }
        }

        private void classifyEnds() {
            for (int at = 0; at < types.length; at++) {
                if (types[at] == 0 || Integer.bitCount(masks[at] & 0xf) != 1) continue;
                if (types[at] == V3RoadType.TERTIARY.ordinal()) {
                    reasons[at] = (byte) V3EndReason.LOCAL_ACCESS.ordinal();
                    continue;
                }
                Direction connected = null;
                for (Direction direction : Direction.VALUES) {
                    if ((masks[at] & direction.bit()) != 0) connected = direction;
                }
                int nx = at % size + connected.opposite().dx();
                int nz = at / size + connected.opposite().dz();
                V3Facts next = fact(nx, nz);
                V3Facts own = fact(at % size, at / size);
                reasons[at] = (byte) blockedReason(next, own.cityLevel()).ordinal();
            }
        }

        private byte[] edgeClasses() {
            byte[] edges = new byte[types.length * Direction.COUNT];
            for (int at = 0; at < types.length; at++) {
                if (types[at] == 0) continue;
                int x = at % size;
                int z = at / size;
                for (Direction direction : Direction.VALUES) {
                    if ((masks[at] & direction.bit()) == 0) continue;
                    V3RoadType neighbour = roadAt(x + direction.dx(), z + direction.dz());
                    if (!neighbour.isRoad()) {
                        throw new IllegalStateException("V3 edge points to a missing road at " + x + "," + z);
                    }
                    // A planned edge is a promise that the two chunks are one continuous surface.
                    // Two different city levels are 6+ blocks apart, so such an edge could only be
                    // a step nobody can climb: refuse it loudly instead of publishing it.
                    if (fact(x + direction.dx(), z + direction.dz()).cityLevel() != fact(x, z).cityLevel()) {
                        throw new IllegalStateException("V3 edge crosses a city level at " + x + "," + z);
                    }
                    edges[at * Direction.COUNT + direction.ordinal()] = (byte) V3RoadType.shared(
                            TYPES[types[at] & 0xff], neighbour).ordinal();
                }
            }
            return edges;
        }
    }

    private record Merge(int[] interior, long rank) { }

    private record ParcelOption(int x, int z, V3Footprint footprint) { }

    private static final class ParcelArea {
        private final int ax;
        private final int az;
        private final List<ParcelOption> viable = new ArrayList<>();
        private boolean originallyFeasible;

        private ParcelArea(int ax, int az) {
            this.ax = ax;
            this.az = az;
        }

    }

    private record Local(int[] cells, int[] path, int faceA, int faceB, boolean through,
                         int residualDepth, int otherResidualDepth, long rank, int bends) { }

    private record CrossRoute(Local route, AreaScore score) { }

    private record Port(int host, int first, Direction inward) { }

    private record Crossing(int host, int firstA, int firstB, int faceA, int faceB,
                            Direction towardA) { }

    private static final class Face {
        private final int id;
        private final List<Integer> cells = new ArrayList<>();
        private final int[] distance;
        private boolean closed = true;
        private int minX = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxZ = Integer.MIN_VALUE;
        private int maxDepth;
        private int deepest = -1;

        Face(int id, int size) {
            this.id = id;
            this.distance = new int[size * size];
            Arrays.fill(distance, Integer.MAX_VALUE);
        }
    }
}
