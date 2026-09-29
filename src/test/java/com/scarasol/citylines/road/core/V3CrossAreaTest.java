package com.scarasol.citylines.road.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V3CrossAreaTest {
    @Test
    void countsOnlyTheTenByTenAreasContainingTertiaryCells() {
        V3Params params = V3Params.selectedForDefaultArea();
        int size = params.supercellSize();
        assertEquals(1, V3Planner.distinctLocalAreas(new int[] {1 + size, 9 + size * 9}, params));
        assertEquals(2, V3Planner.distinctLocalAreas(new int[] {9 + size * 9, 10 + size * 9}, params));
        assertEquals(3, V3Planner.distinctLocalAreas(new int[] {
                9 + size * 9, 10 + size * 9, 20 + size * 9}, params));
    }

    @Test
    void merelyTouchingAnAreaDoesNotCountAsADeepCrossing() {
        V3Params params = V3Params.selectedForDefaultArea();
        int size = params.supercellSize();
        int[] cells = {8 + size * 9, 9 + size * 9, 10 + size * 9,
                11 + size * 9, 12 + size * 9, 13 + size * 9, 20 + size * 9};
        V3Planner.AreaScore score = V3Planner.localAreaScore(cells, params);
        assertEquals(1, score.effectiveAreas());
        assertEquals(1, score.minimumCells());
    }
}
