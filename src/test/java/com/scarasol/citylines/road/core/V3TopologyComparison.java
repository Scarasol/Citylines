package com.scarasol.citylines.road.core;

import mcjty.lostcities.worldgen.street.HierarchicalStreetPlanner;
import mcjty.lostcities.worldgen.street.PlannedRoadType;
import mcjty.lostcities.worldgen.street.StreetPlannerSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Same open-city abstract field for TLC 7.5.5 and candidate V3 parameters.
 *
 * <p>This is a <em>descriptive</em> comparison, not a threshold: it measures road share,
 * closed-face area percentiles, junction degrees and 3x3 parcel coverage on identical
 * 120x120 inputs. {@code main} prints the table;
 * {@code V3TopologyComparisonTest} pins the measured figures of the default V3
 * parameter block so the abstract evidence cannot drift silently.
 *
 * <p>Documented boundary (V3 handover): these numbers contain no terrain, no assets and
 * no generation cost, and there is no agreed realism threshold, so they can never by
 * themselves justify calling K=3 too sparse or switching to K=2.
 */
public final class V3TopologyComparison {
    static final int SIZE = 120;
    static final long[] SEEDS = {0, 1, 31, 101};
    static final List<V3Footprint> FOOTPRINTS = List.of(
            new V3Footprint(3, 3), new V3Footprint(2, 2), new V3Footprint(2, 1));

    private V3TopologyComparison() { }

    /** One measured row: which planner or parameter block, and its aggregate metrics. */
    record Measurement(String label, Metrics metrics) { }

    /** All measured rows, in the order {@code main} prints them. */
    static List<Measurement> compare() {
        List<Measurement> rows = new ArrayList<>();
        Metrics tlc = new Metrics();
        for (long seed : SEEDS) {
            tlc.add(tlcGrid(seed));
        }
        rows.add(new Measurement("TLC", tlc));
        for (int factor : new int[] {2, 3}) {
            for (int collectors : new int[] {1, 2}) {
                for (double merge : new double[] {0, 0.7}) {
                    for (int through : new int[] {5, 6}) {
                        V3Params p = new V3Params(10, factor, 1, collectors, 6, merge,
                                3, through, 4, 2, 6, 5, 2, 10, V3Params.CURRENT_VERSION);
                        Metrics v3 = new Metrics();
                        for (long seed : SEEDS) v3.add(v3Grid(seed, p));
                        rows.add(new Measurement(String.format(Locale.ROOT,
                                "V3 K=%d C=1..%d merge=%.1f through=%d", factor, collectors, merge, through),
                                v3));
                    }
                }
            }
        }
        return rows;
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("default")) {
            Metrics v3 = new Metrics();
            for (long seed : SEEDS) v3.add(v3Grid(seed, V3Params.selectedForDefaultArea()));
            System.out.printf(Locale.ROOT, "V3 default %s%n", v3);
            return;
        }
        for (Measurement row : compare()) {
            System.out.printf(Locale.ROOT, "%s %s%n", row.label(), row.metrics());
        }
    }

    static Grid tlcGrid(long seed) {
        StreetPlannerSettings defaults = new StreetPlannerSettings(8, 8, 0.45f, 4,
                0, 2, 0, 2, 4, 3, 0.40f, 2, 5);
        HierarchicalStreetPlanner planner = new HierarchicalStreetPlanner(seed, "abstract", defaults);
        int stride = SIZE + 2;
        int[] sampled = new int[stride * stride];
        for (int z = -1; z <= SIZE; z++) {
            for (int x = -1; x <= SIZE; x++) {
                PlannedRoadType type = planner.getRoadType(x, z);
                sampled[(z + 1) * stride + x + 1] = type.ordinal();
            }
        }
        Grid grid = new Grid();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int at = z * SIZE + x;
                grid.types[at] = sampled[(z + 1) * stride + x + 1];
                if (grid.types[at] == 0) continue;
                for (Direction direction : Direction.VALUES) {
                    if (sampled[(z + direction.dz() + 1) * stride + x + direction.dx() + 1] != 0) {
                        grid.masks[at] |= direction.bit();
                    }
                }
            }
        }
        return grid;
    }

    static Grid v3Grid(long seed, V3Params params) {
        V3Planner planner = new V3Planner(seed, "abstract", params,
                (x, z) -> V3Facts.city(0), FOOTPRINTS);
        Grid grid = new Grid();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                V3CellInfo info = planner.infoAt(x, z);
                int at = z * SIZE + x;
                grid.types[at] = info.roadType().ordinal();
                grid.masks[at] = info.edgeMask();
            }
        }
        return grid;
    }

    static final class Grid {
        final int[] types = new int[SIZE * SIZE];
        final int[] masks = new int[SIZE * SIZE];
    }

    static final class Metrics {
        private final int[] grades = new int[4];
        private final List<Integer> faceAreas = new ArrayList<>();
        private int ends;
        private int threeWays;
        private int fourWays;
        private int parcels;

        /** Total sampled cells: {@link #SIZE} squared per seed. */
        int cells() {
            return SIZE * SIZE * SEEDS.length;
        }

        int primary() {
            return grades[3];
        }

        int secondary() {
            return grades[2];
        }

        int tertiary() {
            return grades[1];
        }

        int roads() {
            return primary() + secondary() + tertiary();
        }

        /** Road share in percent, computed from the integer chunk counts so it is reproducible. */
        double roadPercent() {
            return 100.0 * roads() / cells();
        }

        int faces() {
            return faceAreas.size();
        }

        int areaP50() {
            return percentile(0.5);
        }

        int areaP90() {
            return percentile(0.9);
        }

        int threeWays() {
            return threeWays;
        }

        int fourWays() {
            return fourWays;
        }

        int ends() {
            return ends;
        }

        int parcels() {
            return parcels;
        }

        /** 3x3-parcel blocks that could exist: one per {@code SIZE/10} block per seed. */
        int parcelBlocks() {
            return SEEDS.length * (SIZE / 10) * (SIZE / 10);
        }

        void add(Grid grid) {
            for (int at = 0; at < grid.types.length; at++) {
                grades[grid.types[at]]++;
                if (grid.types[at] == 0) continue;
                int degree = Integer.bitCount(grid.masks[at] & 0xf);
                if (degree == 1) ends++;
                if (degree == 3) threeWays++;
                if (degree == 4) fourWays++;
            }
            for (int az = 0; az < SIZE; az += 10) {
                for (int ax = 0; ax < SIZE; ax += 10) {
                    if (hasParcel(grid, ax, az)) parcels++;
                }
            }
            collectFaces(grid);
        }

        private boolean hasParcel(Grid grid, int ax, int az) {
            for (int z = az; z <= az + 7; z++) {
                for (int x = ax; x <= ax + 7; x++) {
                    boolean free = true;
                    for (int dz = 0; dz < 3 && free; dz++) {
                        for (int dx = 0; dx < 3; dx++) {
                            if (grid.types[(z + dz) * SIZE + x + dx] != 0) {
                                free = false;
                                break;
                            }
                        }
                    }
                    if (free) return true;
                }
            }
            return false;
        }

        private void collectFaces(Grid grid) {
            boolean[] visited = new boolean[SIZE * SIZE * Direction.COUNT];
            for (int at = 0; at < grid.types.length; at++) {
                for (Direction direction : Direction.VALUES) {
                    if ((grid.masks[at] & direction.bit()) == 0
                            || visited[at * Direction.COUNT + direction.ordinal()]) continue;
                    int start = at;
                    Direction startDirection = direction;
                    int position = at;
                    Direction heading = direction;
                    long twiceArea = 0;
                    boolean complete = false;
                    for (int steps = 0; steps < visited.length; steps++) {
                        int mark = position * Direction.COUNT + heading.ordinal();
                        if (visited[mark]) break;
                        visited[mark] = true;
                        int x = position % SIZE;
                        int z = position / SIZE;
                        int nx = x + heading.dx();
                        int nz = z + heading.dz();
                        if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) break;
                        twiceArea += (long) x * nz - (long) nx * z;
                        position = nz * SIZE + nx;
                        Direction back = heading.opposite();
                        Direction next = null;
                        for (int turn = 1; turn <= 4; turn++) {
                            Direction option = Direction.of(back.ordinal() - turn);
                            if ((grid.masks[position] & option.bit()) != 0) {
                                next = option;
                                break;
                            }
                        }
                        if (next == null) break;
                        heading = next;
                        if (position == start && heading == startDirection) {
                            complete = true;
                            break;
                        }
                    }
                    if (complete && twiceArea > 0) faceAreas.add((int) (twiceArea / 2));
                }
            }
        }

        @Override
        public String toString() {
            Collections.sort(faceAreas);
            return String.format(Locale.ROOT,
                    "road=%.1f%% P/S/T=%d/%d/%d faces=%d areaP50/P90=%d/%d junction3/4=%d/%d ends=%d parcels=%d/%d",
                    roadPercent(), primary(), secondary(), tertiary(),
                    faces(), areaP50(), areaP90(), threeWays, fourWays,
                    ends, parcels, parcelBlocks());
        }

        private int percentile(double fraction) {
            // Sorted here rather than in toString() so the accessors are order-independent.
            Collections.sort(faceAreas);
            return faceAreas.isEmpty() ? 0 : faceAreas.get((int) Math.floor((faceAreas.size() - 1) * fraction));
        }
    }
}
