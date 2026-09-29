package com.scarasol.citylines.mixin.tlc;

import com.scarasol.citylines.road.tlc.RoadSurfaceHooks;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes over the street surface for Citylines chunks.
 *
 * <p>All four injections are {@code HEAD} + {@code cancellable}:
 * <ul>
 *   <li>{@code generateNormalStreetSection} (and the {@code FULL} variant, which
 *       exists in 7.5.5 but is never selected for a hierarchical planned road) place
 *       the Citylines part from {@code RoadPartTable} and cancel, which also skips
 *       {@code generateMinorStreetConnectors} — the TLC single-column connector the
 *       design retires;</li>
 *   <li>{@code generateRandomVegetation} and {@code generateStreetDecorations} are
 *       cancelled only when the decoration would be written into the Citylines cross-section
 *       (carriageway / kerb / the pedestrian band that the design keeps clear).</li>
 * </ul>
 *
 * <p>Nothing here runs for a non-Citylines dimension: the hooks return without cancelling,
 * so Lost Cities keeps its own code path bit for bit. Park sections, borders and
 * building front parts are deliberately not touched: the ports of a Citylines piece only
 * ever border another same-level planned road chunk (where {@code doBorder} is
 * already false), and the front parts are allowed inside the walk band by design.
 *
 * <p>Slope sections ({@code generateStreetSlopeSection}) are also left alone: Citylines has
 * no stair assets in this stage and cross-level connections are outside its
 * connectivity promise, so those chunks keep the TLC stair part.
 */
@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class LostCityTerrainFeatureMixin {

    @Inject(method = "generateNormalStreetSection", at = @At("HEAD"), cancellable = true)
    private void citylines$roadNormalStreetSection(BuildingInfo info, int height, CallbackInfo ci) {
        if (RoadSurfaceHooks.placeRoadSection((LostCityTerrainFeature) (Object) this, info, height)) {
            ci.cancel();
        }
    }

    @Inject(method = "generateFullStreetSection", at = @At("HEAD"), cancellable = true)
    private void citylines$roadFullStreetSection(BuildingInfo info, int height, CallbackInfo ci) {
        if (RoadSurfaceHooks.placeRoadSection((LostCityTerrainFeature) (Object) this, info, height)) {
            ci.cancel();
        }
    }

    @Inject(method = "generateRandomVegetation", at = @At("HEAD"), cancellable = true)
    private void citylines$roadVegetation(BuildingInfo info, int height, CallbackInfo ci) {
        if (RoadSurfaceHooks.suppressVegetation(info)) {
            ci.cancel();
        }
    }

    @Inject(method = "generateStreetDecorations", at = @At("HEAD"), cancellable = true)
    private void citylines$roadStreetDecorations(BuildingInfo info, CallbackInfo ci) {
        if (RoadSurfaceHooks.suppressStairs(info)) {
            ci.cancel();
        }
    }
}
