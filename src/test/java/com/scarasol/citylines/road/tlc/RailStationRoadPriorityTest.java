package com.scarasol.citylines.road.tlc;

import mcjty.lostcities.api.RailChunkType;
import mcjty.lostcities.worldgen.lost.Railway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailStationRoadPriorityTest {
    @Test
    void aClaimedStationBecomesThroughRailAndAnApproachConflictGoesUnderground() {
        Railway.RailChunkInfo surface = new Railway.RailChunkInfo(
                RailChunkType.STATION_SURFACE, Railway.RailDirection.BI, 0, 2);
        Railway.RailChunkInfo through = RailStationRoadPriority.decide(surface, -3, true, true);
        assertEquals(RailChunkType.HORIZONTAL, through.getType());
        assertEquals(-3, through.getLevel());
        assertEquals(2, through.getRails());

        Railway.RailChunkInfo underground = RailStationRoadPriority.decide(surface, -3, false, true);
        assertEquals(RailChunkType.STATION_UNDERGROUND, underground.getType());
        assertEquals(-3, underground.getLevel());
        assertSame(surface, RailStationRoadPriority.decide(surface, -3, false, false));
    }

    @Test
    void onlyRailPiecesThatReachOrClearTheStreetAreObstacles() {
        assertTrue(RailStationRoadPriority.occupiesRoadSurface(RailChunkType.STATION_UNDERGROUND, -3, 0));
        assertTrue(RailStationRoadPriority.occupiesRoadSurface(RailChunkType.STATION_EXTENSION_SURFACE, -1, 0));
        assertTrue(RailStationRoadPriority.occupiesRoadSurface(RailChunkType.GOING_DOWN_ONE_FROM_SURFACE, -1, 0));
        assertFalse(RailStationRoadPriority.occupiesRoadSurface(RailChunkType.GOING_DOWN_FURTHER, -3, 0));
        assertFalse(RailStationRoadPriority.occupiesRoadSurface(RailChunkType.HORIZONTAL, -3, 0));
    }
}
