package com.scarasol.citylines.terrain;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnHeightsTest {
    @Test
    void riseFallAndEmptyReplacePreviousValues() {
        int[] heights = {300, 300, 300, 300};
        ColumnHeights.scan(-64, 319, y -> y <= 71 ? 15 : 0, heights);
        assertArrayEquals(new int[] {72, 72, 72, 72}, heights);
        ColumnHeights.scan(-64, 319, y -> y <= 20 ? 15 : 0, heights);
        assertArrayEquals(new int[] {21, 21, 21, 21}, heights);
        ColumnHeights.scan(-64, 319, y -> 0, heights);
        assertArrayEquals(new int[] {-64, -64, -64, -64}, heights);
        ColumnHeights.scan(-64, 319, y -> y == 319 ? 15 : 0, heights);
        assertArrayEquals(new int[] {320, 320, 320, 320}, heights);
    }

    @Test
    void waterAndLeavesKeepDistinctPredicates() {
        int[] heights = new int[4];
        // WORLD_SURFACE, OCEAN_FLOOR, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES.
        ColumnHeights.scan(-64, 100, y -> y == 100 ? 7 : y >= 64 && y <= 70 ? 13 : y == 63 ? 15 : 0,
                heights);
        assertArrayEquals(new int[] {101, 101, 101, 71}, heights);
        ColumnHeights.scan(-64, 80, y -> y >= 64 && y <= 70 ? 13 : y == 63 ? 15 : 0, heights);
        assertArrayEquals(new int[] {71, 64, 71, 71}, heights);
    }

    @Test
    void oneTraversalStopsAfterAllFourPredicatesResolve() {
        AtomicInteger calls = new AtomicInteger();
        int[] heights = new int[4];
        ColumnHeights.scan(-64, 100, y -> {
            calls.incrementAndGet();
            return y == 98 ? 15 : 0;
        }, heights);
        assertEquals(3, calls.get());
        assertArrayEquals(new int[] {99, 99, 99, 99}, heights);
    }
}
