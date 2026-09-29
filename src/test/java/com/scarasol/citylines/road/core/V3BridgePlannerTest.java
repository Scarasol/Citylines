package com.scarasol.citylines.road.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V3BridgePlannerTest {
    private static final V3CellInfo NONE = new V3CellInfo(V3RoadType.NONE,
            V3RoadType.NONE, V3RoadType.NONE, V3RoadType.NONE, V3RoadType.NONE,
            false, V3EndReason.NONE, 0);

    private static V3CellInfo road(Direction inland) {
        V3RoadType[] edges = {V3RoadType.NONE, V3RoadType.NONE, V3RoadType.NONE, V3RoadType.NONE};
        edges[inland.ordinal()] = V3RoadType.PRIMARY;
        return new V3CellInfo(V3RoadType.PRIMARY, edges[0], edges[1], edges[2], edges[3],
                false, V3EndReason.WATER, 0);
    }

    private static V3BridgeInput input(Axis axis, int gap, boolean farShore, boolean levelZero) {
        return new V3BridgeInput() {
            private int along(int x, int z) { return axis == Axis.X ? x : z; }
            private int fixed(int x, int z) { return axis == Axis.X ? z : x; }
            @Override public ChunkFacts factsAt(int x, int z) {
                if (fixed(x, z) != 0) return ChunkFacts.EMPTY;
                int a = along(x, z);
                if (a == 0 || (a == gap + 1 && farShore)) return ChunkFacts.city(levelZero ? 0 : 1);
                return a > 0 && a <= gap ? ChunkFacts.water() : ChunkFacts.EMPTY;
            }
            @Override public V3CellInfo roadAt(int x, int z) {
                if (fixed(x, z) != 0) return NONE;
                int a = along(x, z);
                if (a == 0) return road(axis == Axis.X ? Direction.W : Direction.N);
                if (a == gap + 1 && farShore) return road(axis == Axis.X ? Direction.E : Direction.S);
                return NONE;
            }
        };
    }

    @Test
    void bothAxesPublishTheSameWholeSpanFromEveryCellAndQueryOrder() {
        for (Axis axis : Axis.values()) {
            for (int gap : new int[] {1, 2, 5, 12}) {
                var forward = new V3BridgePlanner(V3BridgeParams.defaults(),
                        input(axis, gap, true, true), BridgePortValidator.acceptAll(), 1);
                var reverse = new V3BridgePlanner(V3BridgeParams.defaults(),
                        input(axis, gap, true, true), BridgePortValidator.acceptAll(), 1);
                BridgeSpan expected = new BridgeSpan(axis, 0, 0, gap + 1);
                for (int a = 0; a <= gap + 1; a++) {
                    Cell f = axis.cell(a, 0);
                    Cell r = axis.cell(gap + 1 - a, 0);
                    var first = forward.decisionAt(f.x(), f.z());
                    var second = reverse.decisionAt(r.x(), r.z());
                    assertNotNull(first);
                    assertEquals(expected, first.span());
                    assertEquals(first, second);
                }
            }
        }
    }

    @Test
    void invalidSpansAreAbsentAtBothShores() {
        for (var input : new V3BridgeInput[] {input(Axis.X, 13, true, true),
                input(Axis.X, 3, false, true), input(Axis.X, 3, true, false)}) {
            var planner = new V3BridgePlanner(V3BridgeParams.defaults(), input, BridgePortValidator.acceptAll());
            assertNull(planner.decisionAt(0, 0));
            assertNull(planner.decisionAt(4, 0));
            assertNull(planner.decisionAt(14, 0));
        }
        var noPort = new V3BridgePlanner(V3BridgeParams.defaults(), input(Axis.X, 3, true, true),
                (shore, axis, outward) -> outward != Direction.W);
        assertNull(noPort.decisionAt(0, 0));
        assertNull(noPort.decisionAt(4, 0));
    }
}
