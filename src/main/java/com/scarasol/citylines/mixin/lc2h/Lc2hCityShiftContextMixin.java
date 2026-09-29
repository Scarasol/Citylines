package com.scarasol.citylines.mixin.lc2h;

import com.scarasol.citylines.terrain.TerrainOwnership;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Null is LC2H's existing no-shift contract, checked before prewarming or scheduling noise work. */
@Mixin(targets = "org.admany.lc2h.worldgen.terrain.CityShiftField", remap = false)
public abstract class Lc2hCityShiftContextMixin {
    @Inject(method = "context", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void citylines$noCompetingShift(IDimensionInfo provider, LostCityProfile profile,
                                                  @Coerce Object sampler,
                                                  CallbackInfoReturnable<Object> callback) {
        if (TerrainOwnership.isManaged(provider)) {
            callback.setReturnValue(null);
        }
    }
}
