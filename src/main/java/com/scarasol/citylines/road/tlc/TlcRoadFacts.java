package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.road.core.ChunkFacts;
import com.scarasol.citylines.road.core.FactSource;
import mcjty.lostcities.api.RailChunkType;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.varia.Tools;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.Highway;
import mcjty.lostcities.worldgen.lost.Railway;

/**
 * Lost Cities backed {@link FactSource}.
 *
 * <p>Only <b>frozen, order-independent</b> Lost Cities queries are used here:
 * {@code isCityRaw}, {@code getCityLevel}, the heightmap, the profile ground and water
 * levels, the predefined-asset occupancy snapshot, the highway level and the railway chunk
 * type. The railway type is TLC's own cached per-chunk query
 * ({@code Railway.getRailChunkType}, a {@code ConcurrentHashMap} keyed by
 * dimension + chunk, already used by every {@code BuildingInfo}), so asking for it
 * here is no more expensive than world generation itself. Final
 * {@code BuildingInfo} values, {@code MultiChunk} decisions and request-time
 * structure avoidance are deliberately never consulted — that would make the plan
 * depend on which chunk was generated first.
 *
 * <p>Results are cached per chunk because several of these calls are expensive
 * (heightmap, biome lookup); the cache lives in {@code FactCache}, this class only
 * performs the raw queries.
 */
public final class TlcRoadFacts implements FactSource {

    private final IDimensionInfo provider;
    private final int waterLevel;
    private final int cityGroundLevel;

    public TlcRoadFacts(IDimensionInfo provider) {
        this.provider = provider;
        // TLC's own per-chunk water level (BuildingInfo L868-876): the profile's
        // SEALEVEL when it is set, otherwise the dimension's sea level. A Citylines dimension
        // is never a citysphere "outside" chunk (space/spheres are refused by the
        // activation gate), so the dimension profile is the right source here.
        LostCityProfile profile = provider.getProfile();
        this.waterLevel = waterLevel(profile == null ? -1 : profile.SEALEVEL,
                Tools.getSeaLevel(provider.getWorld()));
        // The same BuildingInfo block takes the city ground level from the dimension profile.
        // A missing profile cannot happen for a V3-active dimension (the activation gate
        // resolves the world style before the planner exists); falling back to the water line
        // then keeps city chunks dry instead of flooding a dimension we know nothing about.
        this.cityGroundLevel = profile == null ? this.waterLevel : profile.GROUNDLEVEL;
    }

    @Override
    public ChunkFacts factsAt(int chunkX, int chunkZ) {
        ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
        LostCityProfile profile = BuildingInfo.getProfile(coord, provider);

        boolean cityRaw = BuildingInfo.isCityRaw(coord, provider, profile);
        int cityLevel = BuildingInfo.getCityLevel(coord, provider);

        ChunkHeightmap heightmap = provider.getHeightmap(coord);
        // Raw terrain, not the working value: the border correction rewrites the cached heightmap
        // (plan §4.4), and the water fact must not depend on generation order. When no snapshot exists
        // the height is unknown, and an unknown column is treated as water (the conservative answer for
        // road planning) instead of being read from the corrected working value.
        Integer rawHeight = com.scarasol.citylines.terrain.RawHeightSnapshot.rawOrNull(heightmap);
        if (rawHeight == null) {
            com.scarasol.citylines.terrain.TerrainFlattening.warnUnknownRawHeight(coord);
        }
        boolean belowWaterLine = rawHeight == null || belowWaterLine(rawHeight, waterLevel);
        boolean water = surfaceWater(cityRaw, waterLevel, cityGroundLevel, belowWaterLine);

        // A predefined BUILDING is a hard obstacle; a predefined STREET is not - it is
        // traversable land and a usable anchor. TLC's City.isChunkOccupied() merges
        // both maps, so it must not be used for hardBlocked (it made the ASSET_ANCHOR
        // terminal dead code in real generation).
        boolean predefinedBuilding = City.getPredefinedBuilding(provider, coord) != null;
        boolean predefinedStreet = City.getPredefinedStreet(provider, coord) != null;

        // TLC's own street-suppression decision, replicated from
        // LostCityTerrainFeature.generateStreet (L1169-1172):
        //   canDoStreetOrPark = highwayXLevel != cityLevel && highwayZLevel != cityLevel
        //       && railType != STATION_SURFACE
        //       && (railType != STATION_EXTENSION_SURFACE || railLevel < cityLevel)
        // This is TLC's initial street predicate. The later railway pass can still erase
        // that pavement, so chunkFacts also excludes rail pieces that reach the surface.
        Railway.RailChunkInfo railInfo = Railway.getRailChunkType(coord, provider, profile);
        boolean highwayConflict = highwayBlocksStreet(cityLevel,
                Highway.getXHighwayLevel(coord, provider, profile),
                Highway.getZHighwayLevel(coord, provider, profile));
        boolean railBlocksStreet = railBlocksStreet(cityLevel, railInfo.getType(), railInfo.getLevel());

        return chunkFacts(cityRaw, cityLevel, !water, predefinedBuilding, predefinedStreet,
                highwayConflict, railBlocksStreet, water, belowWaterLine,
                railInfo.getType(), railInfo.getLevel());
    }

    /**
     * Pure water-level precedence, mirroring {@code BuildingInfo.waterLevel}
     * ({@code BuildingInfo.java} L868-875):
     * {@code wl == -1 ? Tools.getSeaLevel(world) : wl}, where {@code wl} is the
     * profile's {@code SEALEVEL}. The {@code -1} sentinel means "use the dimension's
     * sea level"; {@code 0} is a real sea level and must not be treated as a sentinel.
     *
     * <p>Ignoring an explicit profile sea level made flooded profiles (for example
     * {@code atlantis}, whose {@code SEALEVEL} is above the city ground level) report
     * terrain as land that TLC actually floods, so {@code water}/{{@code belowWater}}
     * and therefore every bridge gap were wrong.
     */
    static int waterLevel(int profileSeaLevel, int worldSeaLevel) {
        return profileSeaLevel == -1 ? worldSeaLevel : profileSeaLevel;
    }

    /**
     * Pure below-water-line test. The height is TLC's {@code OCEAN_FLOOR} heightmap
     * sample (the floor under water, not the water surface), so a cell is below the
     * line only when its floor is strictly lower than the chosen water level.
     */
    static boolean belowWaterLine(int terrainHeight, int waterLevel) {
        return terrainHeight < waterLevel;
    }

    /**
     * TLC's own surface-water rule for a <b>city</b> chunk: water exists there only when the water
     * table stands strictly above the city ground level ({@code LostCityTerrainFeature.doCityChunk}
     * L920-927, "special case for a high water level" — a drowned profile). In every other case TLC
     * levels the chunk to {@code getCityGroundLevel()} (L930-942) and its surface is dry.
     *
     * <p>This is what makes the pre-city {@code OCEAN_FLOOR} height unusable inside a city: a former
     * river bed, lake floor or shoreline that the city has filled and paved is <em>land</em>, and
     * calling it water severs the mandatory axis crossing it and hands the chunk to a building —
     * exactly the defect observed in game at a collector over a filled river bed.
     */
    static boolean cityWater(int waterLevel, int groundLevel) {
        return waterLevel > groundLevel;
    }

    /**
     * The water fact the planner sees, mirroring where Lost Cities actually puts water:
     * <ul>
     *   <li>a city chunk is water exactly for a drowned profile ({@link #cityWater});</li>
     *   <li>a chunk outside the city keeps the vanilla terrain
     *       ({@code LostCityTerrainFeature.doNormalChunk} L417-438), so the natural water line
     *       still applies there.</li>
     * </ul>
     *
     * <p>A water <em>biome</em> alone never makes a cell water: {@code isWaterBiome} (L795-803) is
     * true for beaches and rivers, and a levelled city chunk keeps its beach or river biome while
     * being dry pavement.
     */
    static boolean surfaceWater(boolean city, int waterLevel, int groundLevel, boolean belowWaterLine) {
        return city ? cityWater(waterLevel, groundLevel) : belowWaterLine;
    }

    /**
     * Pure highway half of TLC's {@code canDoStreetOrPark}:
     * {@code highwayXLevel == cityLevel || highwayZLevel == cityLevel}
     * ({@code LostCityTerrainFeature.java} L1170-1171). A highway level of {@code -1}
     * never equals a city level, so chunks away from a highway are unaffected.
     */
    static boolean highwayBlocksStreet(int cityLevel, int highwayXLevel, int highwayZLevel) {
        return highwayXLevel == cityLevel || highwayZLevel == cityLevel;
    }

    /**
     * Pure rail half of TLC's {@code canDoStreetOrPark}: no street on a surface station
     * and none on a surface extension at or above the city level
     * ({@code LostCityTerrainFeature.java} L1171-1172). An underground station keeps
     * its surface street, which TLC explicitly allows
     * ({@code BuildingInfo.java} L556 only forbids a building above it).
     */
    static boolean railBlocksStreet(int cityLevel, RailChunkType type, int railLevel) {
        return ChunkFacts.city(cityLevel, type, railLevel).railBlocksStreet();
    }

    /**
     * Pure composition of the planner-facing facts, so the flag semantics can be
     * unit-tested without a live {@link IDimensionInfo}.
     *
     * <p>{@code hardBlocked} means a frozen obstacle or later railway structure covers
     * this chunk. A predefined
     * street alone is <b>not</b> hard blocked — it is land with a usable anchor; and
     * the highway conflict is its own flag because the highway is rendered by TLC, not
     * an obstacle the planner may route around in the same component.
     *
     * <p>The raw railway type and level are carried through unmodified so that a
     * consumer can still tell a <em>debateable surface entrance</em> from a plain
     * obstacle; {@link ChunkFacts#railBlocksStreet()} and
     * {@link ChunkFacts#stationEntrance()} are the two verdicts derived from them.
     */
    static ChunkFacts chunkFacts(boolean cityRaw, int cityLevel, boolean land, boolean predefinedBuilding,
                                 boolean predefinedStreet, boolean highwayConflict, boolean railBlocksStreet,
                                 boolean water, boolean belowWater,
                                 RailChunkType railType, int railLevel) {
        return new ChunkFacts(
                cityRaw,
                true,                                       // one dimension plans under one profile
                cityLevel,
                land,
                predefinedBuilding || railBlocksStreet
                        || RailStationRoadPriority.occupiesRoadSurface(railType, railLevel, cityLevel),
                predefinedStreet,                           // usable as an anchor when connectable
                highwayConflict,
                water,
                belowWater,
                railType,
                railLevel
        );
    }

    public IDimensionInfo provider() {
        return provider;
    }

    public int waterLevel() {
        return waterLevel;
    }
}
