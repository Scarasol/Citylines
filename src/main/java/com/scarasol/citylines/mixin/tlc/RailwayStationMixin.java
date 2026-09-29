package com.scarasol.citylines.mixin.tlc;

import com.scarasol.citylines.road.tlc.RailStationRoadPriority;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.Railway;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = Railway.class, remap = false)
public abstract class RailwayStationMixin {
    @Inject(method = "getStationType", at = @At("RETURN"), cancellable = true)
    private static void citylines$roadPriorityAtStation(ChunkCoord coord, IDimensionInfo provider,
                                                         LostCityProfile profile, float random, int rails,
                                                         List<String> part,
                                                         CallbackInfoReturnable<Railway.RailChunkInfo> cir) {
        cir.setReturnValue(RailStationRoadPriority.resolve(coord, provider, profile, cir.getReturnValue()));
    }
}
