package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.config.CitylinesConfig;
import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.street.EffectiveStreetResolver;
import mcjty.lostcities.worldgen.street.PlannedRoadType;

/**
 * V3 answers for the two Lost Cities rules that would otherwise overwrite the plan.
 *
 * <p>Both methods are strict no-ops for dimensions that do not run the planner: the mixins
 * check {@link CitylinesRoadState#of(IDimensionInfo)} first and fall through to the
 * original bytecode, so a TLC dimension is untouched.
 *
 * <p>Nothing here re-implements a Lost Cities decision:
 * <ul>
 *   <li>{@link #effectiveRoadType} feeds the V3 effective graph into Lost Cities'
 *       own {@link EffectiveStreetResolver}. Every veto of
 *       {@code BuildingInfo.getEffectivePlannedRoadType} (predefined building or
 *       street, accepted multi-building, and {@code isCity}) is still supplied by
 *       that method and is passed through unchanged — only the
 *       "has one adjacent raw city chunk" input is replaced, because that TLC
 *       heuristic is exactly what a connectivity-first network must not inherit.</li>
 *   <li>{@link #refuseMultiBuildingOnReservedRoad} refuses at the consumption site of
 *       {@code MultiChunk}, which is the only place reachable both through Lost
 *       Cities' own {@code canPlaceBuilding} and through LC2H's fast multi-chunk
 *       planner.</li>
 * </ul>
 */
public final class V3RoadRights {

    private V3RoadRights() {
    }

    /**
     * Citylines replacement for the neighbour-rule input of
     * {@code EffectiveStreetResolver.resolve}: a chunk keeps its planned class when at
     * least one of its four shared edges is a planned edge.
     *
     * @param state                                 the dimension's Citylines state, never {@code null}
     * @param coord                                 chunk whose characteristics are being resolved
     * @param rawRoadType                           raw class the planner reported (logical class in an active dimension)
     * @param currentChunkIsCity                    Lost Cities' own veto: unchanged
     * @param nativeHasConnectedCityNeighbor            the neighbour verdict Lost Cities computed itself; only used
     *                                              to count (in diagnostics) how often the planned mask differs from TLC
     * @param overriddenByHigherPrecedenceContent   predefined content / accepted multi-building: unchanged
     */
    public static PlannedRoadType effectiveRoadType(CitylinesRoadState state, ChunkCoord coord,
                                                    PlannedRoadType rawRoadType, boolean currentChunkIsCity,
                                                    boolean nativeHasConnectedCityNeighbor,
                                                    boolean overriddenByHigherPrecedenceContent) {
        boolean hasPlannedEdge = state.roadReservedAt(coord.chunkX(), coord.chunkZ());
        PlannedRoadType result = EffectiveStreetResolver.resolve(rawRoadType, currentChunkIsCity, hasPlannedEdge,
                overriddenByHigherPrecedenceContent);
        if (CitylinesConfig.INSTANCE.debugLogging()) {
            PlannedRoadType v1Result = EffectiveStreetResolver.resolve(rawRoadType, currentChunkIsCity,
                    nativeHasConnectedCityNeighbor, overriddenByHigherPrecedenceContent);
            RoadDiagnostics.recordEffective(state, coord, state.v3Cell(coord.chunkX(), coord.chunkZ()),
                    rawRoadType, hasPlannedEdge, nativeHasConnectedCityNeighbor, v1Result, result);
        }
        return result;
    }

    /** True when the whole multi-building footprint overlaps a reserved V3 road. */
    public static boolean footprintTouchesReservedRoad(CitylinesRoadState state, ChunkCoord topLeft, int dimX, int dimZ) {
        return V3RoadGraph.footprintTouchesReservedRoad(
                (chunkX, chunkZ) -> state.v3Cell(chunkX, chunkZ),
                topLeft.chunkX(), topLeft.chunkZ(), dimX, dimZ);
    }

    /**
     * Consumes an accepted multi-building section and refuses it when its footprint
     * overlaps a reserved Citylines road: {@code multiPos} becomes {@link MultiPos#SINGLE}
     * and {@code multiBuilding} becomes {@code null}, which is exactly the state Lost
     * Cities leaves behind for a chunk that has no multi-building.
     *
     * <p>Refusing here — rather than replacing the placement algorithm — is the only
     * implementable seam: {@code MultiChunk.MB}/{@code buildingGrid} are package
     * private, so a cross-package mixin cannot correct the recorded grid, and LC2H's
     * fast planner never runs {@code canPlaceBuilding} at all. The consequence is a
     * documented, bounded deviation on that path: the refused building still occupies
     * its multichunk slots, so later buildings of the same multichunk area get fewer
     * free positions. On Lost Cities' own path the companion
     * {@code MultiChunk.canPlaceBuilding} veto refuses the candidate <em>before</em> the
     * grid is written, so no slot is consumed there.
     *
     * @return true when the section was refused
     */
    public static boolean refuseMultiBuildingOnReservedRoad(LostChunkCharacteristics characteristics, ChunkCoord coord,
                                                      IDimensionInfo provider) {
        MultiPos multiPos = characteristics.multiPos;
        if (multiPos == null || !multiPos.isMulti()) {
            return false;
        }
        // `initMultiBuildingSection` sets a multi section from two sources: predefined
        // content (City.isChunkOccupied == true) and the automatic MultiChunk placement
        // (false, because the method returns early when the chunk is occupied). Explicit
        // content has always taken precedence over the automatic street field in TLC —
        // getEffectivePlannedRoadType's `overridden` term — so only the automatic
        // placement may be refused. Removing a designer's predefined building would be a
        // regression, not a road right.
        if (City.isChunkOccupied(provider, coord)) {
            return false;
        }
        CitylinesRoadState state = CitylinesRoadState.of(provider);
        if (state == null || !state.isActive()) {
            return false;
        }
        RoadDiagnostics.recordMultiSectionSeen(state);
        // multiPos.x()/z() is the current chunk's offset inside the building, so this
        // recovers the building's own top-left for every chunk of the footprint (the
        // same identity Lost Cities itself uses in getTopLeftCityInfo).
        ChunkCoord topLeft = coord.offset(-multiPos.x(), -multiPos.z());
        if (!footprintTouchesReservedRoad(state, topLeft, multiPos.w(), multiPos.h())) {
            return false;
        }
        characteristics.multiPos = MultiPos.SINGLE;
        characteristics.multiBuilding = null;
        RoadDiagnostics.recordMultiRefusal(state, coord, topLeft, multiPos.w(), multiPos.h());
        return true;
    }
}
