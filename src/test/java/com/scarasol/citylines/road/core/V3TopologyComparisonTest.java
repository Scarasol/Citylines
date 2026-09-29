package com.scarasol.citylines.road.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the abstract V3-vs-TLC comparison so the published evidence is reproducible
 * from {@code tools/gradlew.sh test --offline} instead of only from a hand-run CLI command.
 *
 * <p>What this class is <em>not</em>: it is not a realism threshold. The V3 handover states
 * explicitly that these figures contain no terrain, no assets and no generation cost, and
 * that no agreed threshold exists -- so a change here must never be read as "V3 became too
 * sparse". It only detects that the measured abstract geometry changed.
 *
 * <p>The version-3 figures remain in the knowledge base as the pre-change comparison.
 * These pins describe the version-4 local-road geometry on the same abstract field.
 * They are deterministic: fixed seeds {0, 1, 31, 101}, a fixed 120x120 open-city field,
 * MC-free planners.
 */
class V3TopologyComparisonTest {

    /** Coordinates of the {0, 1, 31, 101} seeds are non-negative, so no border wrapping is needed. */
    private static final long[] SEEDS = V3TopologyComparison.SEEDS;

    /** The default-area parameter block has to be the one the comparison was measured with. */
    private static V3Params defaultParams() {
        V3Params p = V3Params.selectedForDefaultArea();
        assertEquals(10, p.areaSize(), "the comparison is only valid for areasize()==10");
        assertEquals(1, p.minCollectors(), "default block has one collector per axis");
        assertEquals(1, p.maxCollectors(), "C=1..1 is the pinned default row");
        assertEquals(0.7, p.mergeChance(), "merge=0.7 is the pinned default row");
        assertEquals(6, p.throughDepth(), "through=6 is the pinned default row");
        assertEquals(3, p.supercellFactor(), "K=3 is the pinned default row");
        return p;
    }

    @Test
    @DisplayName("the default V3 block reproduces the documented 120x120 open-city figures")
    void defaultBlockReproducesDocumentedFigures() {
        V3TopologyComparison.Metrics v3 = new V3TopologyComparison.Metrics();
        for (long seed : SEEDS) {
            v3.add(V3TopologyComparison.v3Grid(seed, defaultParams()));
        }
        assertEquals(V3TopologyComparison.SIZE * V3TopologyComparison.SIZE * SEEDS.length, v3.cells(),
                "sampled cell count");
        assertEquals(3776, v3.primary(), "documented primary-grade chunk count");
        assertEquals(3074, v3.secondary(), "version-4 secondary-grade chunk count");
        assertEquals(3184, v3.tertiary(), "version-4 local-grade chunk count");
        assertEquals(17.42, v3.roadPercent(), 0.05, "version-4 road share");
        assertEquals(355, v3.faces(), "version-4 closed-face count");
        assertEquals(133, v3.areaP50(), "version-4 median closed-face area");
        assertEquals(190, v3.areaP90(), "version-4 p90 closed-face area");
        assertEquals(576, v3.parcelBlocks(), "3x3 parcel blocks that could exist");
        assertEquals(576, v3.parcels(), "documented 3x3 parcel coverage (576/576)");
    }

    /**
     * The one-road-per-branch parameter variant remains measurable under the current algorithm.
     * Historical pre-D10 figures belong to version 3 and are retained in the knowledge base.
     */
    @Test
    @DisplayName("one local road per branch keeps the version-4 comparison reproducible")
    void oneLocalPerFaceReproducesTheOlderFigures() {
        V3Params p = V3Params.selectedForDefaultArea();
        V3Params single = new V3Params(p.areaSize(), p.supercellFactor(), p.minCollectors(),
                p.maxCollectors(), p.minAxisGap(), p.mergeChance(), p.spurDepth(), p.throughDepth(),
                p.maxSpurLength(), p.detourRadius(), p.maxDetours(), p.localCandidates(), 1,
                p.extraLocalDepth(), p.algorithmVersion());
        V3TopologyComparison.Metrics v3 = new V3TopologyComparison.Metrics();
        for (long seed : SEEDS) {
            v3.add(V3TopologyComparison.v3Grid(seed, single));
        }
        assertEquals(2712, v3.tertiary(), "one-road version-4 local-grade chunk count");
        assertEquals(16.60, v3.roadPercent(), 0.05, "one-road version-4 road share");
    }

    @Test
    @DisplayName("TLC reproduces its documented comparison row")
    void tlcReproducesDocumentedRow() {
        V3TopologyComparison.Metrics tlc = new V3TopologyComparison.Metrics();
        for (long seed : SEEDS) {
            tlc.add(V3TopologyComparison.tlcGrid(seed));
        }
        assertEquals(8320, tlc.primary(), "TLC primary chunks");
        assertEquals(6304, tlc.secondary(), "TLC secondary chunks");
        assertEquals(872, tlc.tertiary(), "TLC local chunks");
        assertEquals(26.9, tlc.roadPercent(), 0.05, "TLC road share");
        assertEquals(999, tlc.faces(), "TLC closed faces");
        assertEquals(27, tlc.areaP50(), "TLC median closed-face area");
        assertEquals(100, tlc.areaP90(), "TLC p90 closed-face area");
        assertEquals(564, tlc.parcels(), "TLC 3x3 parcel coverage is *not* complete");

    }

    @Test
    @DisplayName("V3 retains local roads and every parcel")
    void v3RetainsLocalsAndParcels() {
        V3TopologyComparison.Metrics tlc = new V3TopologyComparison.Metrics();
        V3TopologyComparison.Metrics v3 = new V3TopologyComparison.Metrics();
        for (long seed : SEEDS) {
            tlc.add(V3TopologyComparison.tlcGrid(seed));
            v3.add(V3TopologyComparison.v3Grid(seed, defaultParams()));
        }
        assertTrue(v3.tertiary() > 0, "V3 must lay local roads");
        assertTrue(v3.roadPercent() < tlc.roadPercent(),
                "V3 must stay below TLC's road share: V3=" + v3.roadPercent()
                        + "% TLC=" + tlc.roadPercent() + "%");
        // Descriptive, not a target: V3's fewer, larger blocks are a *different* topology from
        // TLC's denser grid, not a better one. The handover explicitly refuses to call K=3 too
        // sparse without an agreed realism threshold, so this only pins the measured shape.
        assertTrue(v3.areaP50() > tlc.areaP50(),
                "V3's blocks must stay measurably larger than TLC's: V3 p50=" + v3.areaP50()
                        + " TLC p50=" + tlc.areaP50());
        assertTrue(v3.areaP90() > tlc.areaP90(),
                "V3's p90 block must stay measurably larger than TLC's: V3 p90=" + v3.areaP90()
                        + " TLC p90=" + tlc.areaP90());
        assertEquals(tlc.parcelBlocks(), v3.parcels(),
                "V3 must not lose a 3x3 parcel block that TLC keeps");
        assertTrue(v3.parcels() > tlc.parcels(),
                "V3 must recover the 3x3 parcel blocks TLC misses: V3=" + v3.parcels()
                        + " TLC=" + tlc.parcels());
    }

    /**
     * The documented boundary of this comparison: it is descriptive, and no realism threshold
     * is agreed. This pins the <em>relative</em> ordering of the swept parameter blocks so a
     * silent change in the sweep is visible without asserting any block is "correct".
     *
     * <p>Measured facts pinned here: K=2 always places more primary axes and covers more road
     * than the matching K=3 block, and every block keeps all 576 3x3 parcels.
     *
     * <p>Deliberately <em>not</em> pinned, because neither is monotonic in K across this sweep:
     * the local-road count (K=3 lays more local roads in 4 of the 8 matched pairs) and the closed
     * face count (a sparser road set closes <em>fewer</em> distinguishable faces, so K=2 exceeds
     * K=3 on faces in some pairs). Pinning either would encode an accident as a property.
     */
    @Test
    @DisplayName("sweep rows keep their measured ordering: K=3 is uniformly sparser than K=2")
    void sweepRowsKeepDocumentedOrdering() {
        Map<String, V3TopologyComparison.Metrics> rows = new LinkedHashMap<>();
        for (V3TopologyComparison.Measurement row : V3TopologyComparison.compare()) {
            rows.put(row.label(), row.metrics());
        }
        List<String> labels = new ArrayList<>(rows.keySet());
        assertEquals(17, labels.size(), "TLC + 16 V3 sweep rows");
        assertEquals("TLC", labels.get(0), "TLC is the first row");
        assertEquals(16, rows.size() - 1, "the sweep must keep 2 factors x 2 collectors x 2 merge x 2 through");

        for (int collectors = 1; collectors <= 2; collectors++) {
            for (double merge : new double[] {0, 0.7}) {
                for (int through : new int[] {5, 6}) {
                    String where = "C=1.." + collectors + " merge=" + merge + " through=" + through;
                    V3TopologyComparison.Metrics k2 = rows.get(label(2, collectors, merge, through));
                    V3TopologyComparison.Metrics k3 = rows.get(label(3, collectors, merge, through));
                    assertTrue(k2 != null && k3 != null, "missing sweep row for " + where);
                    assertTrue(k2.primary() > k3.primary(),
                            "K=2 must place more primary axes than K=3 at " + where
                                    + ": K2=" + k2.primary() + " K3=" + k3.primary());
                    assertTrue(k3.roadPercent() < k2.roadPercent(),
                            "K=3 must be the sparser block at " + where
                                    + ": K3=" + k3.roadPercent() + "% K2=" + k2.roadPercent() + "%");
                    assertEquals(k2.parcelBlocks(), k2.parcels(),
                            "every K=2 block must keep all 3x3 parcels at " + where);
                    assertEquals(k3.parcelBlocks(), k3.parcels(),
                            "every K=3 block must keep all 3x3 parcels at " + where);
                    assertTrue(k3.tertiary() > 0, "every K=3 block must still lay local roads at " + where);
                }
            }
        }
    }

    /**
     * Structural contract of every sampled grid: a published edge may only point at a cell that
     * is itself a road. A planner that published a half-edge into a non-road cell would break
     * every consumer of {@code edge(Direction)}.
     *
     * <p>The sample is a 120x120 window ({@link V3TopologyComparison#SIZE}) out of a larger
     * world, so edges on the window border legitimately point outside it; those are skipped
     * because their target is not sampled. Cells whose target is inside the window are checked.
     */
    @Test
    @DisplayName("every measured grid publishes only road-to-road edges inside the sample")
    void everyMeasuredGridOnlyConnectsRoadCells() {
        for (long seed : SEEDS) {
            assertEdgeIntegrity("TLC", V3TopologyComparison.tlcGrid(seed), seed);
            assertEdgeIntegrity("V3", V3TopologyComparison.v3Grid(seed, defaultParams()), seed);
        }
    }

    private static void assertEdgeIntegrity(String planner, V3TopologyComparison.Grid grid, long seed) {
        int size = V3TopologyComparison.SIZE;
        int checked = 0;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int at = z * size + x;
                int mask = grid.masks[at] & 0xf;
                for (Direction direction : Direction.VALUES) {
                    if ((mask & direction.bit()) == 0) continue;
                    int nx = x + direction.dx();
                    int nz = z + direction.dz();
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                        // Edge leaves the sampled window; its target is not part of this grid.
                        continue;
                    }
                    checked++;
                    assertTrue(grid.types[nz * size + nx] != 0,
                            planner + " seed=" + seed + " publishes a " + direction
                                    + " edge from " + x + "," + z + " into a non-road cell");
                }
            }
        }
        assertTrue(checked > 0,
                planner + " seed=" + seed + " published no in-sample edge at all, "
                        + "which would make this check vacuous");
    }

    private static String label(int factor, int collectors, double merge, int through) {
        return String.format(java.util.Locale.ROOT,
                "V3 K=%d C=1..%d merge=%.1f through=%d", factor, collectors, merge, through);
    }
}
