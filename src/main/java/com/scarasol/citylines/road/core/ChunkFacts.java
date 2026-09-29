package com.scarasol.citylines.road.core;

import mcjty.lostcities.api.RailChunkType;

/**
 * The frozen, chunk-level facts the planner is allowed to read.
 *
 * <p>This is the entire input surface of the algorithm. The adapter that fills it
 * (Lost Cities backed, or synthetic for the abstract prototype) must not call back
 * into final {@code BuildingInfo}, {@code MultiChunk} or any request-ordered or
 * world-generation-region state: the plan must be a pure function of these facts
 * plus the current planner parameters and the world seed.
 *
 * @param cityRaw          chunk belongs to the raw city mask ({@code isCityRaw})
 * @param profileMatches   chunk uses the same Lost Cities profile as this dimension
 * @param cityLevel        street level of the chunk; only equal levels connect in v1
 * @param land             solid ground at/above the water line (not open water)
 * @param hardBlocked      frozen hard obstacle: predefined building, structure, reserved asset
 * @param predefinedStreet predefined street asset; usable as an anchor when verifiable
 * @param highwayConflict  the intercity highway occupies the same level here
 * @param waterCell        open water (only meaningful for bridge scanning)
 * @param belowWater       terrain below the local water line (bridge span requirement)
 * @param rail             TLC's raw railway chunk type; carried because the surface-entrance
 *                         verdict cannot be recovered from {@link #hardBlocked} alone
 * @param railHeight       TLC's raw railway level; only consulted for
 *                         {@code STATION_EXTENSION_SURFACE}, whose verdict depends on whether
 *                         the rail sits below this chunk's {@link #cityLevel}
 */
public record ChunkFacts(
        boolean cityRaw,
        boolean profileMatches,
        int cityLevel,
        boolean land,
        boolean hardBlocked,
        boolean predefinedStreet,
        boolean highwayConflict,
        boolean waterCell,
        boolean belowWater,
        RailChunkType rail,
        int railHeight
) {

    public ChunkFacts {
        if (rail == null) {
            throw new IllegalArgumentException("rail must not be null");
        }
    }

    public static final ChunkFacts EMPTY =
            new ChunkFacts(false, true, 0, false, false, false, false, false, false, RailChunkType.NONE, 0);

    /** A plain buildable city chunk at the given level, with no railway. */
    public static ChunkFacts city(int cityLevel) {
        return new ChunkFacts(true, true, cityLevel, true, false, false, false, false, false,
                RailChunkType.NONE, 0);
    }

    /** A plain buildable city chunk at the given level that also carries a railway. */
    public static ChunkFacts city(int cityLevel, RailChunkType railType, int railLevel) {
        return new ChunkFacts(true, true, cityLevel, true, false, false, false, false, false,
                railType, railLevel);
    }

    /** A frozen hard obstacle (predefined building or structure) inside the city. */
    public static ChunkFacts blocked(int cityLevel) {
        return new ChunkFacts(true, true, cityLevel, true, true, false, false, false, false,
                RailChunkType.NONE, 0);
    }

    /** Open water, not part of the city mask. */
    public static ChunkFacts water() {
        return new ChunkFacts(false, true, 0, false, false, false, false, true, true,
                RailChunkType.NONE, 0);
    }

    /** Every field of {@code base}, with the listed flags replaced. */
    public static ChunkFacts of(ChunkFacts base, boolean cityRaw, boolean profileMatches, int cityLevel,
                                boolean land, boolean hardBlocked, boolean predefinedStreet,
                                boolean highwayConflict, boolean waterCell, boolean belowWater) {
        return new ChunkFacts(cityRaw, profileMatches, cityLevel, land, hardBlocked, predefinedStreet,
                highwayConflict, waterCell, belowWater, base.rail(), base.railHeight());
    }

    /**
     * A chunk that can carry a planned street <em>inside</em> a district.
     *
     * <p>Water is never pavable: crossing water is an explicit bridge edge, decided
     * by the bridge candidate scan before any routing happens.
     */
    public boolean pavableLand() {
        return cityRaw && profileMatches && land && !hardBlocked && !highwayConflict;
    }

    /** True when this chunk may be used as a bridge head on land. */
    public boolean bridgeShore() {
        return cityRaw && profileMatches && land && !hardBlocked && !highwayConflict && cityLevel == 0;
    }

    /** True when this chunk can be part of a bridge span. */
    public boolean bridgeWater() {
        return waterCell && belowWater && !hardBlocked;
    }

    public boolean sameLevel(ChunkFacts other) {
        return cityLevel == other.cityLevel;
    }

    /**
     * Whether TLC refuses to lay pavement here because of the railway.
     *
     * <p>Exact rail half of {@code LostCityTerrainFeature.canDoStreetOrPark}
     * (L1170-1172), by negation: {@code STATION_SURFACE} always blocks, and
     * {@code STATION_EXTENSION_SURFACE} blocks only when its rail is not strictly
     * below the city level.
     */
    public boolean railBlocksStreet() {
        return rail() == RailChunkType.STATION_SURFACE
                || (rail() == RailChunkType.STATION_EXTENSION_SURFACE && railHeight >= cityLevel);
    }

    /**
     * A subway surface entrance that V3 may adjudicate against a mandatory road axis.
     *
     * <p>This is the strict complement of {@link #railBlocksStreet()} restricted to the
     * station-surface types: the square is an entrance to the underground network, and
     * TLC <em>would</em> lay pavement on it (the rail there sits strictly below the city
     * level). Those two properties together are exactly what makes the square worth
     * debating — a mandatory road axis may claim it, or it may be kept as an entrance.
     *
     * <p>Deliberately excluded, and why:
     * <ul>
     *   <li>{@code STATION_SURFACE} — TLC never lays pavement there, so it is a hard
     *       obstacle and must not masquerade as an adjudicable entrance;</li>
     *   <li>{@code STATION_EXTENSION_SURFACE} at or above the city level — same reason;</li>
     *   <li>the {@code *_UNDERGROUND} types — they do not occupy the surface at all, so
     *       the surface is ordinary pavement and <b>not</b> an entrance.</li>
     * </ul>
     */
    public boolean stationEntrance() {
        return rail() == RailChunkType.STATION_EXTENSION_SURFACE && railHeight < cityLevel;
    }

    /** Edge admission between two neighbouring chunks of the same district owner. */
    public boolean connectsTo(ChunkFacts other) {
        return pavableLand() && other.pavableLand() && profileMatches == other.profileMatches
                && cityLevel == other.cityLevel;
    }
}
