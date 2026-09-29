package com.scarasol.citylines.mixin.tlc;

import com.scarasol.citylines.terrain.RawHeightSnapshot;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes the raw terrain height at the moment the native sampler finishes.
 *
 * <p>{@code generateHeightmap} is the only place that produces a real terrain height: it walks the 16x16
 * columns and ends with {@code update(...)}, and Lost Cities calls it exactly once per heightmap. The
 * return point is therefore the last moment at which the value is known to be the sampler's output and
 * not a city-border correction (plan §4.4.1).
 *
 * <p>Synthetic heightmaps (LC2H's fast candidate scoring, the experimental height branch, the GUI) never
 * run through this method, so they keep {@code NOT_SAMPLED} and can never be mistaken for terrain facts.
 */
@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class LostCityTerrainFeatureHeightmapMixin {

    @Inject(method = "generateHeightmap(IILnet/minecraft/world/level/WorldGenLevel;"
            + "Lmcjty/lostcities/worldgen/ChunkHeightmap;)V", at = @At("RETURN"), require = 1)
    private void citylines$freezeRawHeight(int chunkX, int chunkZ, WorldGenLevel region,
                                           ChunkHeightmap heightmap, CallbackInfo ci) {
        if (heightmap instanceof RawHeightSnapshot holder) {
            holder.citylines$rawHeight(heightmap.getHeight());
        }
    }
}
