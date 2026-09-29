package com.scarasol.citylines.road.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The plan's edge invariant: <b>an explicit edge connects two chunks of the same city level</b>.
 *
 * <p>Two city levels are 6 blocks apart ({@code FLOORHEIGHT}), and every terminal has to be
 * classifiable, so an edge across levels would publish a "flat" road with an unclimbable step in
 * the middle. Citylines asserted this in {@code PlanInvariants}; V3 only asserted that an edge points at
 * an existing road. The through-line of a local road was the one path that never checked it, so
 * this test walks real plans and locks the invariant in.
 */
class V3EdgeLevelInvariantTest {

    /** A synthetic fact source: every cell is city land, with the levels of {@code pattern}. */
    private static V3FactSource source(java.util.function.IntBinaryOperator pattern) {
        return (x, z) -> V3Facts.city(pattern.applyAsInt(x, z));
    }

    /**
     * Walks every explicit edge of every cell and checks the two ends share one level, as reported
     * by the same fact source the plan was built from.
     */
    private static void assertEdgesDoNotCrossLevels(V3Planner planner, V3FactSource facts, int cells) {
        V3Plan plan = planner.plan(new DistrictKey(0, 0));
        int edges = 0;
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                V3CellInfo cell = plan.infoAt(x, z);
                if (!cell.roadType().isRoad()) {
                    continue;
                }
                for (Direction direction : Direction.VALUES) {
                    if (!cell.edge(direction).isRoad()) {
                        continue;
                    }
                    int nx = x + direction.dx();
                    int nz = z + direction.dz();
                    edges++;
                    assertEquals(facts.factsAt(x, z).cityLevel(), facts.factsAt(nx, nz).cityLevel(),
                            "edge " + x + "," + z + " -> " + nx + "," + nz + " crosses a city level");
                }
            }
        }
        assertTrue(edges > 0, "fixture precondition: the plan must actually contain edges");
    }

    @Test
    @DisplayName("a uniform city produces edges, and every one of them stays on its level")
    void uniformCityKeepsTheInvariant() {
        V3FactSource facts = source((x, z) -> 0);
        V3Planner planner = new V3Planner(7, "abstract", V3Params.selectedForDefaultArea(), facts,
                List.of(new V3Footprint(3, 3)));
        assertEdgesDoNotCrossLevels(planner, facts, 30);
    }

    /**
     * The case the through-line used to miss: a closed city block whose interior holds a
     * different level, while the block's bounding roads stay on one level. Before the fix the
     * through line could run straight across that island and publish a cross-level edge.
     */
    @Test
    @DisplayName("a level island inside a block never becomes a cross-level local road")
    void levelIslandInsideABlockStaysOutOfTheLocalRoad() {
        V3FactSource facts = source((x, z) -> (x >= 12 && x <= 17 && z >= 12 && z <= 17) ? 1 : 0);
        V3Planner planner = new V3Planner(11, "abstract", V3Params.selectedForDefaultArea(), facts,
                List.of(new V3Footprint(3, 3)));
        assertEdgesDoNotCrossLevels(planner, facts, 30);
    }

    @Test
    @DisplayName("a level band across the district still yields only same-level edges")
    void levelBandKeepsTheInvariant() {
        V3FactSource facts = source((x, z) -> x < 15 ? 0 : 1);
        V3Planner planner = new V3Planner(13, "abstract", V3Params.selectedForDefaultArea(), facts,
                List.of(new V3Footprint(3, 3)));
        assertEdgesDoNotCrossLevels(planner, facts, 30);
    }

    /** Guards the fixture itself: the island/band patterns must really differ per cell. */
    @Test
    @DisplayName("the synthetic level patterns used above are not uniform")
    void fixturesAreMeaningful() {
        List<Integer> seen = new ArrayList<>();
        for (int x = 0; x < 30; x += 5) {
            seen.add((x >= 12 && x <= 17) ? 1 : 0);
        }
        assertTrue(seen.contains(0) && seen.contains(1));
    }
}
