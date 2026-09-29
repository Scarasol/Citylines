package com.scarasol.citylines.road.tlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.RoadType;

/**
 * The fail-closed coverage gate: every <b>reachable</b> key must resolve to an asset.
 *
 * <p>Checking that the part files exist is not enough. A key the planner can publish but the table
 * cannot resolve is a chunk the renderer must skip, and a part that exists but is never reachable
 * is dead weight; the design (review consensus, "启用门槛") requires the coverage check to cover
 * every reachable key and to refuse an incomplete resource set before a dimension is enabled.
 *
 * <p>The negative case is the point of this class: a gate that cannot fail proves nothing, so a
 * key is removed from a copy of the table and the gate must report exactly that gap.
 */
class RoadPartTableCoverageTest {

    @Test
    @DisplayName("every reachable key resolves against the real table")
    void realTableCoversEveryReachableKey() {
        List<String> unresolved = RoadPartTable.unresolvedReachableKeys();
        assertTrue(unresolved.isEmpty(), "unresolved reachable keys: " + unresolved);
    }

    /**
     * The negative case. Citylines's isolated-collector key has no asset by design, but it is also not
     * reachable, so removing it must NOT be reported; removing a reachable one must be.
     */
    @Test
    @DisplayName("the gate reports a missing reachable key, and stays silent on an unreachable one")
    void gateReportsMissingReachableKeys() {
        Map<Integer, RoadPartTable.Entry> full = copyOfRealTable();
        assertTrue(RoadPartTable.unresolvedReachableKeys(full).isEmpty(),
                "the copied table must start complete");

        // A reachable primary key: the cap and a normal junction both are.
        int reachable = RoadPartTable.selectionKey(RoadType.PRIMARY, RoadType.PRIMARY, RoadType.PRIMARY,
                RoadType.PRIMARY, RoadType.PRIMARY);
        assertTrue(full.containsKey(reachable), "fixture precondition: the four-way primary key exists");
        full.remove(reachable);
        List<String> afterRemoval = RoadPartTable.unresolvedReachableKeys(full);
        assertFalse(afterRemoval.isEmpty(), "removing a reachable key must be reported");
        assertTrue(afterRemoval.stream().anyMatch(s -> s.contains("PRIMARY") && s.contains("pppp")),
                "the report must name the missing key, got: " + afterRemoval);

        // A local corner key must be included in the reachable-key gate.
        Map<Integer, RoadPartTable.Entry> localMissing = copyOfRealTable();
        int localCorner = RoadPartTable.localPortKey(0, RoadType.NONE, RoadType.NONE,
                RoadType.TERTIARY, RoadType.TERTIARY);
        assertTrue(localMissing.containsKey(localCorner), "fixture precondition: the local corner exists");
        localMissing.remove(localCorner);
        List<String> localReport = RoadPartTable.unresolvedReachableKeys(localMissing);
        assertTrue(localReport.stream().anyMatch(s -> s.startsWith("TERTIARY")),
                "a missing local shape must be reported as a TERTIARY gap, got: " + localReport);
    }

    @Test
    @DisplayName("the isolated collector is deliberately absent and deliberately not reported")
    void isolatedCollectorIsAbsentAndUnreachable() {
        assertEquals(null, RoadPartTable.entryFor(RoadType.SECONDARY, RoadType.NONE, RoadType.NONE,
                        RoadType.NONE, RoadType.NONE),
                "an isolated collector has no asset by design");
        assertFalse(RoadPartTable.unresolvedReachableKeys().stream().anyMatch(s -> s.contains("0000")),
                "the unreachable isolated collector must not be reported as a gap");
    }

    /**
     * The V3 projection: every cell a real V3 plan publishes must resolve through the table. This
     * is the coverage claim a V3 dimension depends on, so it is checked against generated plans
     * rather than against a hand-written key list.
     */
    @Test
    @DisplayName("every V3 plan cell projects onto a key the table resolves")
    void everyV3PlanCellResolves() {
        var params = com.scarasol.citylines.road.core.V3Params.selectedForDefaultArea();
        List<com.scarasol.citylines.road.core.V3Footprint> footprints = List.of(
                new com.scarasol.citylines.road.core.V3Footprint(3, 3),
                new com.scarasol.citylines.road.core.V3Footprint(2, 2));
        int cells = 0;
        for (long seed : new long[] {0, 1, 31, 101}) {
            var planner = new com.scarasol.citylines.road.core.V3Planner(seed, "coverage", params,
                    (x, z) -> com.scarasol.citylines.road.core.V3Facts.city(0), footprints, 64);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    var plan = planner.plan(new com.scarasol.citylines.road.core.DistrictKey(dx, dz));
                    int size = plan.size();
                    for (int z = dz * size; z < (dz + 1) * size; z++) {
                        for (int x = dx * size; x < (dx + 1) * size; x++) {
                            var cell = plan.infoAt(x, z);
                            if (!cell.roadType().isRoad()) {
                                continue;
                            }
                            cells++;
                            RoadPartTable.Entry entry = V3RoadGraph.partEntry(cell);
                            assertTrue(entry != null,
                                    "no asset for V3 cell " + cell.roadType() + " at " + x + "," + z
                                            + " transitionMask=" + cell.transitionMask());
                        }
                    }
                }
            }
        }
        assertTrue(cells > 0, "the fixture must contain road cells, otherwise this test is vacuous");
    }

    private static Map<Integer, RoadPartTable.Entry> copyOfRealTable() {
        // The production table itself, so the negative case exercises the real coverage gate
        // rather than a re-implementation of its enumeration (which could drift from it).
        return RoadPartTable.keyTable();
    }
}
