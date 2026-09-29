package com.scarasol.citylines.mixin.lc2h;

import com.scarasol.citylines.terrain.TerrainFlattening;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** LC2H replaces TLC's inner level call; only the uniform city result is intercepted. */
@Mixin(value = BuildingInfo.class, remap = false, priority = 1500)
public abstract class Lc2hCityLevelMixin {
    @Inject(method = "getCityLevel(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;)I",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void citylines$cityLevel(ChunkCoord coord, IDimensionInfo provider,
                                           CallbackInfoReturnable<Integer> callback) {
        int level = TerrainFlattening.flattenedLevel(coord, provider);
        if (level != TerrainFlattening.NO_LEVEL) {
            callback.setReturnValue(level);
        }
    }
}
