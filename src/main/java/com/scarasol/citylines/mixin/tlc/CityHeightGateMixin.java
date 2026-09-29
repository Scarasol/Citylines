package com.scarasol.citylines.mixin.tlc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.scarasol.citylines.terrain.RawHeightSnapshot;
import com.scarasol.citylines.terrain.TerrainFlattening;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.lost.City;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes the city mask's height gates read the frozen raw terrain height inside uniform-plane dimensions.
 *
 * <p>{@code City.getCityFactor} rejects a city when the chunk heightmap is below {@code CITY_MINHEIGHT}
 * or above {@code CITY_MAXHEIGHT}. That heightmap is the cached object the border correction rewrites
 * ({@code correctTerrainShape -> setHeight}), so without this the city domain would depend on whether a
 * neighbouring chunk had already been corrected — the mask would stop being a pure function of the seed
 * and the generated plane could disagree with Lost Cities' own city decision (plan §4.4.3).
 *
 * <p>Scoped to dimensions that actually run the plane: everywhere else the native value is returned
 * untouched. {@code require = 2} pins both gate reads, so a future Lost Cities version that moves or
 * duplicates them fails loudly at load instead of silently keeping the corrected value.
 */
@Mixin(value = City.class, remap = false)
public abstract class CityHeightGateMixin {

    @WrapOperation(method = "getCityFactor",
            at = @At(value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/ChunkHeightmap;getHeight()I"),
            require = 2)
    private static int citylines$rawHeightGate(ChunkHeightmap heightmap, Operation<Integer> original,
                                               @Local(argsOnly = true) ChunkCoord coord) {
        int working = original.call(heightmap);
        if (coord == null || !TerrainFlattening.isUniformActive(coord.dimension())) {
            return working;
        }
        Integer raw = RawHeightSnapshot.rawOrNull(heightmap);
        // No snapshot means "this height is not a terrain fact". Returning the sentinel makes both gates
        // (below MIN / above MAX) reject the chunk instead of judging it by a corrected height, and the
        // flag makes the caller refuse to generate the chunk at all (plan §4.4).
        if (raw == null) {
            TerrainFlattening.noteUnknownRawHeight(coord.dimension(), coord.chunkX(), coord.chunkZ());
            return RawHeightSnapshot.NOT_SAMPLED;
        }
        return raw;
    }
}
