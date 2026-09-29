package com.scarasol.citylines.road.tlc;

import java.util.List;

import mcjty.lostcities.worldgen.street.PlannedRoadType;
import mcjty.lostcities.worldgen.street.PlannedStreetInfo;

import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.RoadType;
import com.scarasol.citylines.road.core.V3CellInfo;
import com.scarasol.citylines.road.core.V3RoadType;

/** Shared effective graph for road types, explicit edges, whole-footprint rights and surface ports. */
public final class V3RoadGraph {

    /**
     * The four "no road here" answers, reused so a road-free chunk allocates nothing.
     * A non-road chunk has no edges by construction, so one shared instance is correct.
     */
    private static final PlannedRoadType NONE = PlannedRoadType.NONE;

    private V3RoadGraph() {
    }

    /**
     * V3 level to Lost Cities level. Identity on ordinals, kept as an explicit switch so a
     * future reordering of either enum fails to compile instead of silently remapping.
     */
    public static PlannedRoadType toPlanned(V3RoadType type) {
        return switch (type) {
            case NONE -> PlannedRoadType.NONE;
            case TERTIARY -> PlannedRoadType.TERTIARY;
            case SECONDARY -> PlannedRoadType.SECONDARY;
            case PRIMARY -> PlannedRoadType.PRIMARY;
        };
    }

    /**
     * The planner-facing answer for {@code HierarchicalStreetPlanner.getStreetInfo}.
     *
     * <p>The four boolean order is TLC's own record order — {@code north, south, west, east}
     * — which is <b>not</b> the {@code N/E/S/W} mask bit order; passing them in mask order
     * would transpose north with east.
     *
     * <p>The block-coordinate fields are filled with the chunk's own position, which is what
     * Citylines does as well: they describe where the primary street line sits inside the chunk, and
     * V3's geometry does not place a primary line inside a non-primary chunk. The lists and
     * density stay empty for the same reason (V3 has no secondary-line or tertiary-segment
     * sub-chunk geometry; its parts are whole-chunk).
     */
    public static PlannedStreetInfo streetInfo(V3CellInfo cell, int chunkX, int chunkZ) {
        if (cell == null || !cell.roadType().isRoad()) {
            return emptyStreetInfo(chunkX, chunkZ);
        }
        return new PlannedStreetInfo(toPlanned(cell.roadType()),
                cell.edge(Direction.N).isRoad(),
                cell.edge(Direction.S).isRoad(),
                cell.edge(Direction.W).isRoad(),
                cell.edge(Direction.E).isRoad(),
                chunkX, chunkZ, chunkX, chunkZ, chunkX, chunkZ, 0.0d, List.of(), List.of(), null);
    }

    private static PlannedStreetInfo emptyStreetInfo(int chunkX, int chunkZ) {
        return new PlannedStreetInfo(NONE, false, false, false, false,
                chunkX, chunkZ, chunkX, chunkZ, chunkX, chunkZ, 0.0d, List.of(), List.of(), null);
    }

    /**
     * The table class a V3 centre is drawn by: a local centre uses the medium family.
     *
     * <p>This is the centre half of the rendering alias. It is exposed so the suppression code can
     * derive the placed piece's ports the same way {@link #partEntry} does, instead of guessing.
     *
     * @return the table centre class, or {@code null} when the cell is not a road
     */
    public static RoadType toTableCentre(V3RoadType type) {
        return switch (type) {
            case NONE -> null;
            case TERTIARY, SECONDARY -> RoadType.SECONDARY;
            case PRIMARY -> RoadType.PRIMARY;
        };
    }

    /**
     * The surface part for one V3 cell, or {@code null} when the table has no piece for it.
     *
     * <p>Two things happen here and nowhere else, which is what keeps the alias from leaking into
     * the logical graph:
     * <ul>
     *   <li>the centre class is projected onto the table's classes, including the local
     *       ({@code TERTIARY}) family;</li>
     *   <li>each arm is resolved to its <b>geometric port</b> using the plan's own
     *       {@code transitionMask}: a local cell's entrance arm carries the neighbour's medium
     *       section while its purely local arms stay narrow. The transition bit is only consulted
     *       for an arm that actually has an edge.</li>
     * </ul>
     *
     * <p>The logical edge class is never modified here, and neither is the road right: this is a
     * rendering projection, exactly as the review consensus requires.
     */
    public static RoadPartTable.Entry partEntry(V3CellInfo cell) {
        if (cell == null || !cell.roadType().isRoad()) {
            return null;
        }
        if (cell.roadType() == V3RoadType.TERTIARY) {
            // Local centre: the table knows the four reachable local shapes and their rotations.
            // The arms are the cell's own edges -- a local cell is degree 1 or 2, so passing all
            // four as TERTIARY would describe a junction that never exists and find no asset.
            RoadType[] localArms = new RoadType[Direction.COUNT];
            for (Direction direction : Direction.VALUES) {
                localArms[direction.ordinal()] = cell.edge(direction).isRoad()
                        ? RoadType.TERTIARY
                        : RoadType.NONE;
            }
            return RoadPartTable.entryForLocal(localArms[0], localArms[1], localArms[2], localArms[3],
                    cell.transitionMask());
        }
        RoadType centre = toTableCentre(cell.roadType());
        RoadType[] arms = new RoadType[Direction.COUNT];
        for (Direction direction : Direction.VALUES) {
            V3RoadType edge = cell.edge(direction);
            if (!edge.isRoad()) {
                arms[direction.ordinal()] = RoadType.NONE;
                continue;
            }
            // A local arm at a higher-class centre presents the medium port.
            arms[direction.ordinal()] = edge == V3RoadType.PRIMARY ? RoadType.PRIMARY : RoadType.SECONDARY;
        }
        return RoadPartTable.entryFor(centre, arms[0], arms[1], arms[2], arms[3]);
    }

    /**
     * Whether this cell reserves its chunk against an automatic multi-building footprint.
     *
     * <p>This is the V3 form of the reserved-road predicate, and deliberately the
     * <b>same rule</b>: a chunk counts as a road only when it carries a road class
     * <em>and</em> at least one explicit planned edge. A class without an edge is a chunk
     * whose road will not be rendered, so refusing a building for it would be a phantom
     * veto — and keeping the two rules identical is what stops the road-type consumer and
     * the footprint consumer from disagreeing.
     *
     * <p>All three road levels reserve their occupied cells.
     */
    public static boolean isReservedRoad(V3CellInfo cell) {
        return cell != null && cell.roadType().isRoad() && cell.edgeMask() != 0;
    }

    /**
     * True when any chunk of the axis-aligned footprint overlaps a reserved V3 road.
     *
     * <p>Only chunks that actually overlap the footprint count: a road merely adjacent to it
     * does not block it. Every coordinate is answered by the plan that owns it, so a
     * footprint straddling a supercell border is still evaluated against one coherent graph.
     */
    public static boolean footprintTouchesReservedRoad(V3PlannerAccess planner, int topLeftChunkX,
                                                       int topLeftChunkZ, int dimX, int dimZ) {
        for (int x = 0; x < dimX; x++) {
            for (int z = 0; z < dimZ; z++) {
                if (isReservedRoad(planner.infoAt(topLeftChunkX + x, topLeftChunkZ + z))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when any chunk of the axis-aligned footprint overlaps a reserved road.
     *
     * <p>The predicate is V3's own ({@link #isReservedRoad}), so the road-type consumer and this
     * one cannot disagree about what "a road" means — which is the failure mode the V3 handover
     * calls out (trimming in one consumer only). Only chunks that actually overlap the footprint
     * count: a road merely adjacent to it does not block it.
     *
     * <p>The footprint is walked in absolute chunk coordinates, so a building straddling a
     * supercell border is still evaluated against one coherent graph: every coordinate is answered
     * by the plan that owns it, not by the plan of the chunk that happened to be asked first.
     */

    /**
     * Access to the dimension's V3 planner, kept as a one-method interface so the effective
     * graph has no compile-time dependency on how the planner is stored or reached.
     */
    @FunctionalInterface
    public interface V3PlannerAccess {
        V3CellInfo infoAt(int chunkX, int chunkZ);
    }
}
