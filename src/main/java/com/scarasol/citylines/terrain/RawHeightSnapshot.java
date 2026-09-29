package com.scarasol.citylines.terrain;

import mcjty.lostcities.worldgen.ChunkHeightmap;

/**
 * The raw terrain height of one Lost Cities chunk heightmap, frozen at the moment the native sampler
 * produced it.
 *
 * <h2>Why a snapshot is needed</h2>
 *
 * {@code ChunkHeightmap} objects are cached per chunk by
 * {@code LostCityTerrainFeature.getHeightmap(...)}, and the border-correction pass
 * ({@code doNormalChunk} → {@code correctTerrainShape}) calls {@code setHeight(...)} on the very
 * object it received from that cache. Any later reader of {@code getHeight()} therefore sees the
 * <b>corrected</b> height, not the terrain the noise generator produced — which makes the city mask's
 * height gates ({@code City.getCityFactor} → {@code CITY_MINHEIGHT}/{@code CITY_MAXHEIGHT}) and the
 * road water facts depend on whether a neighbouring chunk happened to be corrected first.
 *
 * <p>Recording the value at the return point of {@code generateHeightmap} keeps the raw number
 * available without changing {@code getHeight()} semantics anywhere: terrain and border rendering
 * keep reading the working value, while every decision that must be a pure function of the seed reads
 * the snapshot.
 *
 * <p>Implemented by a narrow mixin on {@code ChunkHeightmap}; the interface exists so callers can use
 * a {@code instanceof} check instead of reflection, and so the snapshot survives the copy constructor
 * that Lost Cities uses to fill a whole height-sampling group.
 */
public interface RawHeightSnapshot {

    /** Sentinel for "this heightmap has no snapshot" (heights are never {@link Integer#MIN_VALUE}). */
    int NOT_SAMPLED = Integer.MIN_VALUE;

    /** The frozen raw height, or {@link #NOT_SAMPLED}. */
    int citylines$rawHeight();

    /** Records the frozen raw height; ignored by the terrain pipeline afterwards. */
    void citylines$rawHeight(int height);

    /**
     * The raw height of a heightmap, or {@code null} when none was established.
     *
     * <p>Callers must decide what an unknown height means; silently substituting the working value is
     * forbidden, because that value can already have been rewritten by the city-border correction and
     * would reintroduce exactly the generation-order dependency this snapshot exists to remove
     * (plan §4.4). Synthetic heightmaps (LC2H's fast candidate scoring, the experimental height branch,
     * the GUI) never carry a snapshot, so they answer {@code null} by construction.
     */
    static Integer rawOrNull(ChunkHeightmap heightmap) {
        if (heightmap instanceof RawHeightSnapshot holder) {
            int raw = holder.citylines$rawHeight();
            if (raw != NOT_SAMPLED) {
                return raw;
            }
        }
        return null;
    }
}
