package com.scarasol.citylines.road.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Local-road coverage uses 10x10 areas, not the share of chunks paved as local roads. */
class V3LocalRoadCoverageTest {

    private static final long[] SEEDS = {0L, 1L, 31L};

    private record Tally(int closedFaces, int openFaces, int localsApplied, int noRouteFaces,
                         int blocksWithLocal, int blocks) {
    }

    private static Tally measure(V3FactSource facts) {
        int closed = 0;
        int open = 0;
        int locals = 0;
        int noRoute = 0;
        int blocks = 0;
        int withLocal = 0;
        for (long seed : SEEDS) {
            V3Planner planner = new V3Planner(seed, "abstract", V3Params.selectedForDefaultArea(), facts,
                    List.of(new V3Footprint(3, 3), new V3Footprint(2, 2), new V3Footprint(2, 1)));
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    V3Plan plan = planner.plan(new DistrictKey(dx, dz));
                    V3Plan.Stats stats = plan.stats();
                    closed += stats.preLocalClosedAreas();
                    open += stats.preLocalOpenAreas();
                    locals += stats.localThrough() + stats.localSpurs();
                    noRoute += stats.preRescueNoLocalRouteAreas();
                    int size = plan.size();
                    for (int bx = 0; bx < size; bx += 10) {
                        for (int bz = 0; bz < size; bz += 10) {
                            blocks++;
                            boolean any = false;
                            for (int x = bx; x < Math.min(bx + 10, size); x++) {
                                for (int z = bz; z < Math.min(bz + 10, size); z++) {
                                    if (plan.roadTypeAt(plan.key().originX(size) + x,
                                            plan.key().originZ(size) + z) == V3RoadType.TERTIARY) {
                                        any = true;
                                    }
                                }
                            }
                            if (any) {
                                withLocal++;
                            }
                        }
                    }
                }
            }
        }
        return new Tally(closed, open, locals, noRoute, withLocal, blocks);
    }

    @Test
    @DisplayName("in a flat city every closed face gets a local road, so depth is not the limit")
    void flatCityClosesEveryFaceAndPavesIt() {
        Tally flat = measure((x, z) -> V3Facts.city(0));
        assertTrue(flat.closedFaces() > 0, "fixture precondition: the flat city must have closed faces");
        assertEquals(0, flat.openFaces(), "a flat city with no obstacle has no open face");
        assertEquals(0, flat.noRouteFaces(),
                "no closed face may be left without a route: the depth thresholds are not the limiter");
        assertTrue(flat.localsApplied() >= flat.closedFaces(),
                "every closed face must receive at least one local road (D10 may add a second)");
        assertTrue(flat.blocksWithLocal() * 100 / flat.blocks() >= 50,
                "a flat abstract city should reach most 10x10 blocks, measured 57.6%");
    }

    @Test
    @DisplayName("an open face can connect two higher-road ports without paving its obstacle")
    void openFaceCanCarryThroughRoad() {
        V3Planner planner = new V3Planner(0, "abstract", V3Params.selectedForDefaultArea(),
                (x, z) -> x == 2 && z == 2 ? V3Facts.blocked(0) : V3Facts.city(0),
                List.of(new V3Footprint(3, 3), new V3Footprint(2, 2), new V3Footprint(2, 1)));
        V3Plan plan = planner.plan(new DistrictKey(0, 0));
        assertTrue(plan.stats().preLocalOpenAreas() > 0);

        int localCells = 0;
        int parentPorts = 0;
        int maxX = planner.collectorOffsets(0, true)[0];
        int maxZ = planner.collectorOffsets(0, false)[0];
        for (int z = 1; z < maxZ; z++) {
            for (int x = 1; x < maxX; x++) {
                if (plan.roadTypeAt(x, z) == V3RoadType.TERTIARY) {
                    localCells++;
                    if (plan.transitionMaskAt(x, z) != 0) parentPorts++;
                }
            }
        }
        assertTrue(localCells > 0, "the open face should receive a local route");
        assertEquals(2, parentPorts, "the route must connect to higher roads at both ends");
        assertEquals(V3RoadType.NONE, plan.roadTypeAt(2, 2));
        assertEquals(0, plan.stats().lostParcelAreas());
    }
}
