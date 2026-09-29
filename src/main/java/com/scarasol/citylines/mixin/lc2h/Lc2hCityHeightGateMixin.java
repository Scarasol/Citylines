package com.scarasol.citylines.mixin.lc2h;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.scarasol.citylines.terrain.Lc2hRawHeightAdapter;
import com.scarasol.citylines.terrain.RawHeightSnapshot;
import com.scarasol.citylines.terrain.TerrainFlattening;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.City;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * LC2H counterpart of {@code CityHeightGateMixin} (plan §4.4.3/§4.4.4).
 *
 * <p>LC2H {@code @Overwrite}s {@code City.getCityFactor} and moves the city-height gates into the
 * {@code @Unique} helpers {@code lc2h$getCityFactorUncached} and {@code lc2h$finishNormalCityFactor}.
 * Each height gate's hot-path check is forced false only for a managed dimension, including
 * its activation precheck. It then uses provider.getHeightmap and the immutable raw snapshot. The
 * earlier tile-cache hot-path check and all other natural-height-cache consumers remain unchanged.
 * Missing heightmaps or snapshots throw before a fabricated factor can enter LC2H's cache.
 *
 * <p>{@code priority = 1500} makes this mixin apply <b>after</b> LC2H's default-priority one: the helper
 * methods only exist once LC2H merged them into {@code City}. {@code require = 1} per injection means a
 * future LC2H version that renames or moves a gate fails loudly at load instead of silently keeping the
 * cache-dependent behaviour — and the binding refuses to activate the plane when this adapter is not on
 * {@code City} at all (see {@code CityGroundBinding.lc2hBlocksActivation}).
 *
 * <p>Integration evidence and dependency versions are recorded in the uniform-elevation delivery doc.
 */
@Mixin(value = City.class, remap = false, priority = 1500)
public abstract class Lc2hCityHeightGateMixin implements Lc2hRawHeightAdapter {

    @WrapOperation(method = "lc2h$getCityFactorUncached",
            at = @At(value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/ChunkHeightmap;getHeight()I"),
            require = 1)
    private static int citylines$rawHeightGateUncached(ChunkHeightmap heightmap,
                                                       Operation<Integer> original,
                                                       @Local(argsOnly = true) ChunkCoord coord) {
        Integer raw = citylines$raw(heightmap, coord);
        return raw != null ? raw : original.call(heightmap);
    }

    @WrapOperation(method = "lc2h$finishNormalCityFactor",
            at = @At(value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/ChunkHeightmap;getHeight()I"),
            require = 1)
    private static int citylines$rawHeightGateFinish(ChunkHeightmap heightmap,
                                                     Operation<Integer> original,
                                                     @Local(argsOnly = true) ChunkCoord coord) {
        Integer raw = citylines$raw(heightmap, coord);
        return raw != null ? raw : original.call(heightmap);
    }

    @WrapOperation(method = "lc2h$getCityFactorUncached",
            at = @At(value = "INVOKE",
                    target = "Lorg/admany/lc2h/worldgen/lostcities/PlannerHotPath;shouldAvoidBlocking()Z",
                    ordinal = 1),
            require = 1)
    private static boolean citylines$preciseHeightUncached(Operation<Boolean> original,
                                                           @Local(argsOnly = true) ChunkCoord coord) {
        return !TerrainFlattening.isUniformActive(coord.dimension()) && original.call();
    }

    @WrapOperation(method = "lc2h$finishNormalCityFactor",
            at = @At(value = "INVOKE",
                    target = "Lorg/admany/lc2h/worldgen/lostcities/PlannerHotPath;shouldAvoidBlocking()Z"),
            require = 1)
    private static boolean citylines$preciseHeightFinish(Operation<Boolean> original,
                                                         @Local(argsOnly = true) ChunkCoord coord) {
        return !TerrainFlattening.isUniformActive(coord.dimension()) && original.call();
    }

    /** Null must not become LC2H's "height unknown, skip gate" branch. */
    @WrapOperation(method = {"lc2h$getCityFactorUncached", "lc2h$finishNormalCityFactor"},
            at = @At(value = "INVOKE", target = "Lmcjty/lostcities/worldgen/IDimensionInfo;"
                    + "getHeightmap(Lmcjty/lostcities/varia/ChunkCoord;)Lmcjty/lostcities/worldgen/ChunkHeightmap;"),
            require = 2, allow = 2)
    private static ChunkHeightmap citylines$requireHeightmap(IDimensionInfo provider, ChunkCoord coord,
                                                             Operation<ChunkHeightmap> original) {
        ChunkHeightmap heightmap = original.call(provider, coord);
        if (heightmap == null && TerrainFlattening.isUniformActive(coord.dimension())) {
            TerrainFlattening.noteUnknownRawHeight(coord.dimension(), coord.chunkX(), coord.chunkZ());
        }
        return heightmap;
    }

    /** The frozen raw height of the heightmap the caller already has, or {@code null} when inactive. */
    private static Integer citylines$raw(ChunkHeightmap heightmap, ChunkCoord coord) {
        if (coord == null || !TerrainFlattening.isUniformActive(coord.dimension())) {
            return null;
        }
        Integer raw = RawHeightSnapshot.rawOrNull(heightmap);
        // This latches BROKEN and throws immediately, before LC2H can cache a fabricated answer.
        if (raw == null) {
            TerrainFlattening.noteUnknownRawHeight(coord.dimension(), coord.chunkX(), coord.chunkZ());
            return RawHeightSnapshot.NOT_SAMPLED;
        }
        return raw;
    }
}
