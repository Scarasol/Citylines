package com.scarasol.citylines.mixin.tlc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.citylines.terrain.TerrainFlattening;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cities use the frozen plane; non-city levels retain TLC's own semantics. */
@Mixin(value = BuildingInfo.class, remap = false)
public abstract class BuildingInfoCityLevelMixin {
    @WrapOperation(method = "getCityLevel",
            at = @At(value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/lost/BuildingInfo;getCityLevelLocked(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;)I"),
            require = 1, allow = 1)
    private static int citylines$cityLevel(ChunkCoord coord, IDimensionInfo provider, Operation<Integer> original) {
        int level = TerrainFlattening.flattenedLevel(coord, provider);
        return level != TerrainFlattening.NO_LEVEL ? level : original.call(coord, provider);
    }
}
