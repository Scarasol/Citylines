package com.scarasol.citylines.mixin.tlc;

import com.scarasol.citylines.road.tlc.CitylinesRoadState;
import com.scarasol.citylines.road.tlc.CitylinesStreetPlannerAccess;
import mcjty.lostcities.worldgen.street.HierarchicalStreetPlanner;
import mcjty.lostcities.worldgen.street.PlannedRoadType;
import mcjty.lostcities.worldgen.street.PlannedStreetInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Delegates Lost Cities' raw street queries to the Citylines planner for opted-in
 * dimensions.
 *
 * <p>This single injection point covers every consumer in Lost Cities: the five
 * known call sites ({@code BuildingInfo}, {@code MultiChunk},
 * {@code HierarchicalBridgePlanner} and the debug command) all go through
 * {@code IDimensionInfo.getStreetPlanner()}. The TLC code path is untouched and is
 * used verbatim whenever no Citylines state is attached or the state is inactive, so
 * non-Citylines dimensions keep byte-identical behaviour.
 *
 * <p>Only {@code @Inject} is used (never {@code @Redirect}/{@code @Overwrite}), and
 * the added state is a {@code @Unique} field reached through a compile-time duck
 * interface.
 */
@Mixin(value = HierarchicalStreetPlanner.class, remap = false)
public abstract class HierarchicalStreetPlannerMixin implements CitylinesStreetPlannerAccess {

    @Unique
    private CitylinesRoadState citylines$roadState;

    @Override
    public void citylines$setRoadState(CitylinesRoadState state) {
        this.citylines$roadState = state;
    }

    @Override
    public CitylinesRoadState citylines$roadState() {
        return this.citylines$roadState;
    }

    @Inject(method = "getStreetInfo", at = @At("HEAD"), cancellable = true)
    private void citylines$getStreetInfo(int chunkX, int chunkZ, CallbackInfoReturnable<PlannedStreetInfo> cir) {
        CitylinesRoadState state = this.citylines$roadState;
        if (state != null && state.isActive()) {
            cir.setReturnValue(state.streetInfo(chunkX, chunkZ));
        }
    }

    @Inject(method = "getRoadType", at = @At("HEAD"), cancellable = true)
    private void citylines$getRoadType(int chunkX, int chunkZ, CallbackInfoReturnable<PlannedRoadType> cir) {
        CitylinesRoadState state = this.citylines$roadState;
        if (state != null && state.isActive()) {
            cir.setReturnValue(state.roadType(chunkX, chunkZ));
        }
    }
}
