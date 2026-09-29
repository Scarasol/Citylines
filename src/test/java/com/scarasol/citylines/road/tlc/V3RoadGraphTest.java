package com.scarasol.citylines.road.tlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import mcjty.lostcities.worldgen.street.PlannedRoadType;
import mcjty.lostcities.worldgen.street.PlannedStreetInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.DistrictKey;
import com.scarasol.citylines.road.core.V3EndReason;
import com.scarasol.citylines.road.core.V3CellInfo;
import com.scarasol.citylines.road.core.V3FactSource;
import com.scarasol.citylines.road.core.V3Facts;
import com.scarasol.citylines.road.core.V3Footprint;
import com.scarasol.citylines.road.core.V3Params;
import com.scarasol.citylines.road.core.V3Plan;
import com.scarasol.citylines.road.core.V3Planner;
import com.scarasol.citylines.road.core.V3RoadType;

/**
 * Contract tests for {@link V3RoadGraph} — the V3 effective graph of integration step 4.
 *
 * <p>These use a real {@link V3Planner} over a synthetic open city, so the graph under
 * test is a genuine published {@link V3Plan} rather than hand-built cells. They cover the
 * three properties step 4 is about: the level mapping is exact, edges come from V3's
 * explicit logical edges (never "both cells are roads"), and all three levels reserve
 * against a building footprint.
 */
class V3RoadGraphTest {
    private static V3Planner openCity() {
        V3FactSource city = (x, z) -> V3Facts.city(0);
        return new V3Planner(31, "abstract", V3Params.selectedForDefaultArea(), city,
                List.of(new V3Footprint(3, 3), new V3Footprint(2, 2)));
    }

    // ------------------------------------------------------------- mapping -----

    /**
     * The mapping must be the identity on ordinals. A future reorder of either enum would
     * silently remap every road in the world, so it is pinned name by name.
     */
    @Test
    @DisplayName("every V3 level maps to the same-named Lost Cities level, and NONE to NONE")
    void levelMappingIsExact() {
        assertEquals(PlannedRoadType.NONE, V3RoadGraph.toPlanned(V3RoadType.NONE));
        assertEquals(PlannedRoadType.TERTIARY, V3RoadGraph.toPlanned(V3RoadType.TERTIARY));
        assertEquals(PlannedRoadType.SECONDARY, V3RoadGraph.toPlanned(V3RoadType.SECONDARY));
        assertEquals(PlannedRoadType.PRIMARY, V3RoadGraph.toPlanned(V3RoadType.PRIMARY));
        // The two enums share an order, which is what makes a three-level V3 network
        // expressible to TLC without inventing a level.
        for (V3RoadType type : V3RoadType.values()) {
            assertEquals(type.ordinal(), V3RoadGraph.toPlanned(type).ordinal(),
                    "ordinal order must stay aligned for " + type);
        }
    }

    /** Every level V3 can publish must map onto a real Lost Cities level. */
    @Test
    @DisplayName("a real plan's three levels all survive the mapping")
    void allThreeLevelsSurviveTheMapping() {
        V3Planner planner = openCity();
        java.util.Set<V3RoadType> seen = new java.util.TreeSet<>();
        for (int z = 0; z < 30; z++) {
            for (int x = 0; x < 30; x++) {
                V3RoadType type = planner.roadTypeAt(x, z);
                if (type.isRoad()) {
                    seen.add(type);
                }
            }
        }
        // An EnumSet, so the assertion is on the set of levels and never on scan order,
        // while iteration still follows declaration order (NONE, TERTIARY, SECONDARY, PRIMARY).
        assertEquals(List.of(V3RoadType.PRIMARY, V3RoadType.SECONDARY, V3RoadType.TERTIARY),
                List.copyOf(seen).stream().sorted(java.util.Comparator.reverseOrder()).toList(),
                "an open supercell must publish all three levels (scan order is not asserted)");
        for (V3RoadType type : seen) {
            assertNotNull(V3RoadGraph.toPlanned(type));
        }
    }

    @Test
    @DisplayName("a planned tertiary corner resolves to the new corner part")
    void plannedLocalCornerUsesCornerPart() {
        V3Planner planner = openCity();
        int corners = 0;
        for (int z = 0; z < 90; z++) {
            for (int x = 0; x < 90; x++) {
                V3CellInfo cell = planner.infoAt(x, z);
                if (cell.roadType() != V3RoadType.TERTIARY || Direction.count(cell.edgeMask()) != 2) {
                    continue;
                }
                int mask = cell.edgeMask();
                boolean straight = (mask & (Direction.N.bit() | Direction.S.bit()))
                        == (Direction.N.bit() | Direction.S.bit())
                        || (mask & (Direction.E.bit() | Direction.W.bit()))
                        == (Direction.E.bit() | Direction.W.bit());
                if (straight) continue;
                RoadPartTable.Entry part = V3RoadGraph.partEntry(cell);
                assertNotNull(part, "missing corner part at " + x + "," + z);
                assertEquals("citylines:road_t_00tt", part.part(), "wrong corner part at " + x + "," + z);
                corners++;
            }
        }
        assertTrue(corners > 0, "the abstract fixture must contain a local corner");
    }

    // ------------------------------------------------------------ street info --

    /**
     * The four booleans of {@code PlannedStreetInfo} are ordered
     * {@code north, south, west, east} — deliberately <b>not</b> the {@code N/E/S/W} bit
     * order. Passing them in mask order would transpose north with east, which no other
     * test in this project would catch.
     */
    @Test
    @DisplayName("street-info booleans follow TLC's north/south/west/east order, not mask order")
    void streetInfoBooleansAreNotTransposed() {
        V3Planner planner = openCity();
        int checked = 0;
        for (int z = 0; z < 30 && checked < 40; z++) {
            for (int x = 0; x < 30 && checked < 40; x++) {
                V3CellInfo cell = planner.infoAt(x, z);
                if (!cell.roadType().isRoad()) {
                    continue;
                }
                PlannedStreetInfo info = V3RoadGraph.streetInfo(cell, x, z);
                assertEquals(cell.edge(Direction.N).isRoad(), info.north(), "north at " + x + "," + z);
                assertEquals(cell.edge(Direction.S).isRoad(), info.south(), "south at " + x + "," + z);
                assertEquals(cell.edge(Direction.W).isRoad(), info.west(), "west at " + x + "," + z);
                assertEquals(cell.edge(Direction.E).isRoad(), info.east(), "east at " + x + "," + z);
                checked++;
            }
        }
        assertTrue(checked > 0, "the fixture must contain road cells, otherwise this test is vacuous");
    }

    @Test
    @DisplayName("a non-road chunk reports NONE with no edges at all")
    void nonRoadChunkHasNoEdges() {
        V3Planner planner = openCity();
        for (int z = 0; z < 30; z++) {
            for (int x = 0; x < 30; x++) {
                V3CellInfo cell = planner.infoAt(x, z);
                if (cell.roadType().isRoad()) {
                    continue;
                }
                PlannedStreetInfo info = V3RoadGraph.streetInfo(cell, x, z);
                assertEquals(PlannedRoadType.NONE, info.roadType());
                assertFalse(info.north() || info.south() || info.west() || info.east());
                assertFalse(info.isRoad());
                return;
            }
        }
        throw new AssertionError("fixture has no road-free chunk, so this test proved nothing");
    }

    /**
     * V3's edges are explicit logical edges: two adjacent road cells do <b>not</b> imply a
     * connection. This is the defect Citylines had to introduce {@code RouteEdge} to fix, so the
     * adapter must report exactly the published edge and never a neighbour heuristic.
     *
     * <p>Built directly rather than searched for in a plan: whether a particular fixture
     * happens to contain adjacent-but-disconnected road pairs is a property of the fixture,
     * not of the adapter, and asserting it would make this test fail for the wrong reason.
     */
    @Test
    @DisplayName("a road whose neighbour is also a road is still unconnected without an explicit edge")
    void adjacencyAloneNeverProducesAConnection() {
        // Two adjacent SECONDARY cells, neither publishing the shared edge.
        V3CellInfo west = new V3CellInfo(V3RoadType.SECONDARY, V3RoadType.NONE, V3RoadType.NONE,
                V3RoadType.NONE, V3RoadType.TERTIARY, false, V3EndReason.NONE, 0);
        V3CellInfo east = new V3CellInfo(V3RoadType.SECONDARY, V3RoadType.NONE, V3RoadType.TERTIARY,
                V3RoadType.NONE, V3RoadType.NONE, false, V3EndReason.NONE, 0);

        PlannedStreetInfo westInfo = V3RoadGraph.streetInfo(west, 4, 4);
        assertFalse(westInfo.east(),
                "an adjacent road cell must NOT be reported as connected without an explicit edge");
        assertTrue(westInfo.west(), "the explicit W edge must still be reported");

        // And the symmetric case: with the shared edge published on both sides, it appears.
        V3CellInfo westLinked = new V3CellInfo(V3RoadType.SECONDARY, V3RoadType.NONE, V3RoadType.TERTIARY,
                V3RoadType.NONE, V3RoadType.NONE, false, V3EndReason.NONE, 0);
        V3CellInfo eastLinked = new V3CellInfo(V3RoadType.SECONDARY, V3RoadType.NONE, V3RoadType.NONE,
                V3RoadType.NONE, V3RoadType.TERTIARY, false, V3EndReason.NONE, 0);
        assertTrue(V3RoadGraph.streetInfo(westLinked, 4, 4).east(), "a published E edge must appear");
        assertTrue(V3RoadGraph.streetInfo(eastLinked, 5, 4).west(), "and mirrored on the other side");

        // A wildcard: no edge at all means no connection in any direction, whatever the
        // centre class is -- this is what makes the reservation rule consistent.
        PlannedStreetInfo lonely = V3RoadGraph.streetInfo(west, 4, 4);
        assertEquals(V3RoadType.TERTIARY.isRoad(), lonely.west());
        assertFalse(lonely.north() || lonely.south() || lonely.east());
    }

    // ------------------------------------------------------------- road rights -

    @Test
    @DisplayName("all three levels reserve their chunk once they have a planned edge")
    void everyLevelReservesWithAnEdge() {
        for (V3RoadType type : new V3RoadType[] {V3RoadType.PRIMARY, V3RoadType.SECONDARY,
                V3RoadType.TERTIARY}) {
            V3CellInfo withEdge = new V3CellInfo(type, V3RoadType.TERTIARY, V3RoadType.NONE,
                    V3RoadType.NONE, V3RoadType.NONE, false, V3EndReason.NONE, 0);
            assertTrue(V3RoadGraph.isReservedRoad(withEdge),
                    type + " with a planned edge must reserve the chunk");

            V3CellInfo withoutEdge = new V3CellInfo(type, V3RoadType.NONE, V3RoadType.NONE,
                    V3RoadType.NONE, V3RoadType.NONE, false, V3EndReason.NONE, 0);
            assertFalse(V3RoadGraph.isReservedRoad(withoutEdge),
                    type + " without any planned edge is a phantom road and must reserve nothing");
        }
        assertFalse(V3RoadGraph.isReservedRoad(null), "a null cell reserves nothing");
        assertFalse(V3RoadGraph.isReservedRoad(new V3CellInfo(V3RoadType.NONE, V3RoadType.PRIMARY,
                V3RoadType.NONE, V3RoadType.NONE, V3RoadType.NONE, false,
                V3EndReason.NONE, 0)),
                "a NONE centre reserves nothing even if an edge class leaked in");
    }

    /** The reservation rule must agree with the street-info answer for the same chunk. */
    @Test
    @DisplayName("reservation and street-info agree on which chunks are roads")
    void reservationAgreesWithStreetInfo() {
        V3Planner planner = openCity();
        int roads = 0;
        for (int z = 0; z < 30; z++) {
            for (int x = 0; x < 30; x++) {
                V3CellInfo cell = planner.infoAt(x, z);
                PlannedStreetInfo info = V3RoadGraph.streetInfo(cell, x, z);
                assertEquals(info.isRoad(), cell.roadType().isRoad(),
                        "street-info and the cell must agree on 'is a road' at " + x + "," + z);
                if (V3RoadGraph.isReservedRoad(cell)) {
                    roads++;
                    assertTrue(info.isRoad(),
                            "a reserved chunk must also be reported as a road at " + x + "," + z);
                }
            }
        }
        assertTrue(roads > 0, "fixture must contain reserved roads");
    }

    @Test
    @DisplayName("a footprint overlapping a reserved road is refused, an adjacent one is not")
    void footprintTouchesOnlyOverlappingRoads() {
        V3Planner planner = openCity();
        V3RoadGraph.V3PlannerAccess access = planner::infoAt;

        // Find a reserved road chunk with a road-free neighbour to its +X side.
        int roadX = Integer.MIN_VALUE;
        int roadZ = Integer.MIN_VALUE;
        for (int z = 1; z < 29 && roadX == Integer.MIN_VALUE; z++) {
            for (int x = 1; x < 29; x++) {
                if (V3RoadGraph.isReservedRoad(planner.infoAt(x, z))
                        && !V3RoadGraph.isReservedRoad(planner.infoAt(x + 1, z))) {
                    roadX = x;
                    roadZ = z;
                    break;
                }
            }
        }
        assertTrue(roadX != Integer.MIN_VALUE, "fixture needs a road next to a non-road");

        assertTrue(V3RoadGraph.footprintTouchesReservedRoad(access, roadX, roadZ, 1, 1),
                "a 1x1 footprint exactly on the road must be refused");
        assertTrue(V3RoadGraph.footprintTouchesReservedRoad(access, roadX, roadZ, 2, 2),
                "a 2x2 footprint overlapping the road must be refused");

        // The non-road chunk alone must be free, which proves the refusal above came from
        // the overlap and not from the fixture refusing everything.
        assertFalse(V3RoadGraph.footprintTouchesReservedRoad(access, roadX + 1, roadZ, 1, 1),
                "a 1x1 footprint on the road-free neighbour must not be refused");
    }

    /** Every chunk of a footprint is checked, not just the top-left corner. */
    @Test
    @DisplayName("a road in the far corner of a footprint still refuses it")
    void footprintChecksEveryChunkNotJustTheCorner() {
        V3Planner planner = openCity();
        V3RoadGraph.V3PlannerAccess access = planner::infoAt;
        int roadX = Integer.MIN_VALUE;
        int roadZ = Integer.MIN_VALUE;
        for (int z = 1; z < 27 && roadX == Integer.MIN_VALUE; z++) {
            for (int x = 1; x < 27; x++) {
                if (V3RoadGraph.isReservedRoad(planner.infoAt(x, z))
                        && !V3RoadGraph.isReservedRoad(planner.infoAt(x - 1, z - 1))) {
                    roadX = x;
                    roadZ = z;
                    break;
                }
            }
        }
        assertTrue(roadX != Integer.MIN_VALUE, "fixture needs a road whose top-left neighbour is free");
        // Footprint whose top-left is the free chunk and whose bottom-right is the road.
        assertTrue(V3RoadGraph.footprintTouchesReservedRoad(access, roadX - 1, roadZ - 1, 2, 2),
                "a road in the bottom-right of the footprint must still refuse it");
    }

    /** Sanity: a plan queried through the access interface is the same graph V3 published. */
    @Test
    @DisplayName("the access interface answers from the owning plan, including negative coordinates")
    void accessInterfaceAnswersFromTheOwningPlan() {
        V3Planner planner = openCity();
        V3RoadGraph.V3PlannerAccess access = planner::infoAt;
        for (int[] at : new int[][] {{5, 5}, {-1, -1}, {-35, 17}, {29, 29}, {30, 30}}) {
            V3CellInfo viaAccess = access.infoAt(at[0], at[1]);
            V3Plan owner = planner.plan(DistrictKey.of(at[0], at[1], V3Params.selectedForDefaultArea()
                    .supercellSize()));
            assertEquals(owner.infoAt(at[0], at[1]), viaAccess,
                    "access must return the owner's own answer at " + at[0] + "," + at[1]);
        }
    }
}
