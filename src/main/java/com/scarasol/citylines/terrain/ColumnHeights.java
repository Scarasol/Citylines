package com.scarasol.citylines.terrain;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/** Four height predicates share one downward column traversal and first-free-Y convention. */
final class ColumnHeights {
    private ColumnHeights() {
    }

    static void scan(int minY, int topY, IntUnaryOperator opaqueTypes, int[] heights) {
        Arrays.fill(heights, minY);
        int remaining = 15;
        for (int y = topY; y >= minY && remaining != 0; y--) {
            int hits = opaqueTypes.applyAsInt(y) & remaining;
            for (int bits = hits; bits != 0; bits &= bits - 1) {
                heights[Integer.numberOfTrailingZeros(bits)] = y + 1;
            }
            remaining &= ~hits;
        }
    }
}
