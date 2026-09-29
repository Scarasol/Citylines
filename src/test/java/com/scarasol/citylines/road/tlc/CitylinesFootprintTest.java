package com.scarasol.citylines.road.tlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scarasol.citylines.road.core.V3Footprint;

/**
 * Contract tests for reading the protected multi-building shapes (decision D12).
 *
 * <p>The asset lookup itself needs a loaded datapack, so only the filtering rule is testable here:
 * that is where the contract's "rare footprint" fallback can silently die (a hardcoded list, or a
 * shape wider than the multi-building tile), so it is the part that gets a test.
 */
class CitylinesFootprintTest {

    @Test
    @DisplayName("big datapack shapes survive the filter, oversized ones do not")
    void keepsEveryPlaceableShape() {
        List<V3Footprint> filtered = CitylinesRoadState.footprintsWithin(List.of(
                new V3Footprint(9, 9), new V3Footprint(7, 7), new V3Footprint(3, 3),
                new V3Footprint(2, 2), new V3Footprint(2, 1),
                new V3Footprint(11, 2)), 10);
        assertTrue(filtered.contains(new V3Footprint(9, 9)),
                "a 9x9 asset must reach the planner: it is what switches the rare-footprint fallback on");
        assertTrue(filtered.contains(new V3Footprint(7, 7)));
        assertTrue(filtered.contains(new V3Footprint(3, 3)));
        assertEquals(5, filtered.size(), "11x2 is wider than the multi-building tile and is dropped");
    }

    @Test
    @DisplayName("an empty shape cannot even be built, so the filter never sees one")
    void emptyShapesAreRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new V3Footprint(0, 3));
    }

    @Test
    @DisplayName("duplicate assets of the same size collapse to one shape")
    void deduplicatesShapes() {
        List<V3Footprint> filtered = CitylinesRoadState.footprintsWithin(List.of(
                new V3Footprint(2, 2), new V3Footprint(2, 2), new V3Footprint(2, 2)), 10);
        assertEquals(1, filtered.size());
        assertEquals(new V3Footprint(2, 2), filtered.get(0));
    }

    @Test
    @DisplayName("an asset list narrower than the tile keeps what it has")
    void smallAssetListsStayUsable() {
        List<V3Footprint> filtered = CitylinesRoadState.footprintsWithin(
                List.of(new V3Footprint(2, 1)), 10);
        assertEquals(List.of(new V3Footprint(2, 1)), filtered);
    }

    @Test
    @DisplayName("nothing usable stays empty, so the caller can fall back instead of protecting nothing")
    void unusableListsComeBackEmpty() {
        assertTrue(CitylinesRoadState.footprintsWithin(List.of(new V3Footprint(11, 11)), 10).isEmpty());
        assertTrue(CitylinesRoadState.footprintsWithin(List.of(), 10).isEmpty());
    }
}
