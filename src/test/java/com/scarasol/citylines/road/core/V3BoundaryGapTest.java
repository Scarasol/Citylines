package com.scarasol.citylines.road.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class V3BoundaryGapTest {
    private static final V3Params PARAMS = V3Params.selectedForDefaultArea();
    private static final long SEED = 8218742260916520910L;

    private static V3Planner planner(V3FactSource facts) {
        return new V3Planner(SEED, "minecraft:overworld", PARAMS, facts, List.of());
    }

    private static V3Facts surfaceRail() {
        return new V3Facts(true, 0, true, false, false, true, false, true);
    }

    @Test
    void surfaceStationAtLastRowConnectsAroundTheRailWithoutUsingIt() {
        int x = planner((cx, cz) -> V3Facts.city(0)).collectorOffsets(0, true)[0];
        assertEquals(10, x, "the repair must preserve the existing seed's collector axis");
        V3FactSource facts = (cx, cz) -> {
            if (cz != 29) return V3Facts.city(0);
            if (cx == x || cx == x - 1) return V3Facts.blocked(0);
            if (cx == x + 1 || cx == x - 2) return surfaceRail();
            return V3Facts.city(0);
        };
        V3Planner first = planner(facts);
        V3Plan north = first.planAt(x, 28);
        V3Plan south = first.planAt(x, 30);

        assertEquals(V3RoadType.NONE, north.roadTypeAt(x, 29));
        assertEquals(V3RoadType.NONE, north.roadTypeAt(x + 1, 29));
        assertEquals(V3RoadType.SECONDARY, north.roadTypeAt(x + 2, 29));
        assertTrue((north.edgeMaskAt(x + 1, 28) & Direction.E.bit()) != 0);
        assertTrue((north.edgeMaskAt(x + 2, 28) & Direction.S.bit()) != 0);
        assertTrue((north.edgeMaskAt(x + 2, 29) & Direction.S.bit()) != 0);
        assertTrue((south.edgeMaskAt(x + 2, 30) & Direction.N.bit()) != 0);
        assertTrue((south.edgeMaskAt(x + 2, 30) & Direction.W.bit()) != 0);
        assertTrue((south.edgeMaskAt(x + 1, 30) & Direction.W.bit()) != 0);
        assertTrue((north.edgeMaskAt(x, 28) & Direction.E.bit()) != 0);

        V3Planner reversed = planner(facts);
        V3Plan southFirst = reversed.planAt(x, 30);
        V3Plan northSecond = reversed.planAt(x, 28);
        assertEquals(north.digest(), northSecond.digest());
        assertEquals(south.digest(), southFirst.digest());
    }

    @Test
    void blockedBothSidesLeavesTheStationAndBoundaryDisconnected() {
        int x = planner((cx, cz) -> V3Facts.city(0)).collectorOffsets(0, true)[0];
        V3Planner graph = planner((cx, cz) -> cz == 29 && Math.abs(cx - x) <= 2
                ? V3Facts.blocked(0) : V3Facts.city(0));
        V3Plan north = graph.planAt(x, 28);
        assertEquals(V3RoadType.NONE, north.roadTypeAt(x, 29));
        for (int shift = -2; shift <= 2; shift++) {
            assertFalse((north.edgeMaskAt(x + shift, 28) & Direction.S.bit()) != 0);
        }
    }

    @Test
    void negativeCoordinateSeamUsesTheSameHorizontalCrossingOnBothSides() {
        int z = planner((cx, cz) -> V3Facts.city(0)).collectorOffsets(0, false)[0];
        V3Planner graph = planner((cx, cz) -> {
            if (cx != -1) return V3Facts.city(0);
            if (cz == z || cz == z - 1) return V3Facts.blocked(0);
            if (cz == z + 1 || cz == z - 2) return surfaceRail();
            return V3Facts.city(0);
        });
        V3Plan west = graph.planAt(-1, z);
        V3Plan east = graph.planAt(0, z);
        assertEquals(V3RoadType.NONE, west.roadTypeAt(-1, z));
        assertTrue((west.edgeMaskAt(-1, z + 2) & Direction.E.bit()) != 0);
        assertTrue((east.edgeMaskAt(0, z + 2) & Direction.W.bit()) != 0);
    }
}
