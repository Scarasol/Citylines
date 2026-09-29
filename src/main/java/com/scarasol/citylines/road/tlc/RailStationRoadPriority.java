package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.road.core.V3RoadType;
import mcjty.lostcities.api.RailChunkType;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.varia.Tools;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.Highway;
import mcjty.lostcities.worldgen.lost.Railway;

/** Resolves station/arterial conflicts before TLC derives the adjacent rail pieces. */
public final class RailStationRoadPriority {
    private RailStationRoadPriority() {
    }

    public static Railway.RailChunkInfo resolve(ChunkCoord station, IDimensionInfo provider,
                                                LostCityProfile profile, Railway.RailChunkInfo original) {
        CitylinesRoadState state = CitylinesRoadState.of(provider);
        if (state == null || !state.isActive() || !profile.RAILWAYS_ENABLED
                || !original.getType().isStation()) {
            return original;
        }
        int level = BuildingInfo.getCityLevel(station, provider);
        int seaLevel = profile.SEALEVEL == -1 ? Tools.getSeaLevel(provider.getWorld()) : profile.SEALEVEL;
        if (TlcRoadFacts.cityWater(seaLevel, profile.GROUNDLEVEL)) {
            return original;
        }
        boolean centerClaimed = axisCanCarryRoad(state, station, provider, profile, level);
        if (centerClaimed) {
            return decide(original, Railway.getRailwayLevel(profile), true, false);
        }
        boolean approachClaimed = false;
        if (original.getType() == RailChunkType.STATION_SURFACE) {
            // TLC uses at most one surface extension, followed by the first descending piece.
            for (int distance = 1; distance <= 2; distance++) {
                if (axisCanCarryRoad(state, station.offset(distance, 0), provider, profile, level)
                        || axisCanCarryRoad(state, station.offset(-distance, 0), provider, profile, level)) {
                    approachClaimed = true;
                    break;
                }
            }
        }
        return decide(original, Railway.getRailwayLevel(profile), centerClaimed, approachClaimed);
    }

    static Railway.RailChunkInfo decide(Railway.RailChunkInfo original, int railwayLevel,
                                        boolean centerClaimed, boolean approachClaimed) {
        if (centerClaimed) {
            return new Railway.RailChunkInfo(RailChunkType.HORIZONTAL, Railway.RailDirection.BI,
                    railwayLevel, original.getRails());
        }
        if (approachClaimed && original.getType() == RailChunkType.STATION_SURFACE) {
            return new Railway.RailChunkInfo(RailChunkType.STATION_UNDERGROUND,
                    Railway.RailDirection.BI, railwayLevel, original.getRails());
        }
        return original;
    }

    private static boolean axisCanCarryRoad(CitylinesRoadState state, ChunkCoord coord,
                                            IDimensionInfo provider, LostCityProfile profile, int level) {
        if (state.v3Planner().axisTypeAt(coord.chunkX(), coord.chunkZ()) == V3RoadType.NONE
                || !BuildingInfo.isCityRaw(coord, provider, profile)
                || BuildingInfo.getCityLevel(coord, provider) != level
                || City.getPredefinedBuilding(provider, coord) != null) {
            return false;
        }
        return !TlcRoadFacts.highwayBlocksStreet(level,
                Highway.getXHighwayLevel(coord, provider, profile),
                Highway.getZHighwayLevel(coord, provider, profile));
    }

    /** Railway pieces generated after the street pass that can replace its surface. */
    static boolean occupiesRoadSurface(RailChunkType type, int railLevel, int cityLevel) {
        if (type == RailChunkType.STATION_UNDERGROUND
                || type == RailChunkType.STATION_SURFACE
                || type == RailChunkType.STATION_EXTENSION_SURFACE) {
            return true;
        }
        if (type == RailChunkType.GOING_DOWN_ONE_FROM_SURFACE
                || type == RailChunkType.GOING_DOWN_TWO_FROM_SURFACE
                || type == RailChunkType.GOING_DOWN_FURTHER) {
            return railLevel + 2 >= cityLevel;
        }
        return type != RailChunkType.NONE && railLevel >= cityLevel;
    }
}
