package com.scarasol.citylines.mixin.tlc;

import com.scarasol.citylines.road.tlc.CitylinesRoadState;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.Orientation;
import mcjty.lostcities.worldgen.street.HierarchicalBridgePlanner;
import mcjty.lostcities.worldgen.street.PlannedBridgeInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses the current complete-span bridge overlay in managed dimensions. */
@Mixin(value = HierarchicalBridgePlanner.class, remap = false)
public abstract class HierarchicalBridgePlannerMixin {

    @Inject(method = "getBridgeInfo", at = @At("HEAD"), cancellable = true)
    private static void citylines$noBridgeInActiveDimension(BuildingInfo source, Orientation orientation,
                                                            CallbackInfoReturnable<PlannedBridgeInfo> cir) {
        if (source == null || source.provider == null) {
            return;
        }
        CitylinesRoadState state = CitylinesRoadState.of(source.provider);
        if (state == null || !state.isActive()) {
            return; // a TLC dimension keeps the original planner byte for byte
        }
        cir.setReturnValue(null);
    }
}
