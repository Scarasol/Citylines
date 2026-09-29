package com.scarasol.citylines.mixin.tlc;

import com.scarasol.citylines.road.tlc.CitylinesRoadState;
import com.scarasol.citylines.road.tlc.RoadDiagnostics;
import com.scarasol.citylines.road.tlc.V3RoadRights;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import mcjty.lostcities.worldgen.lost.cityassets.MultiBuilding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses a multi-building candidate in a Citylines dimension when its footprint overlaps a
 * reserved Citylines road.
 *
 * <p>Lost Cities' own road check inside {@code canPlaceBuilding} only runs for
 * {@code HIERARCHICAL_GRID_V1} and asks the configured
 * {@code MultiBuildingStreetConflict} policy — {@code OVERRIDE_MINOR} by default,
 * which refuses only {@code PRIMARY} footprints, so a {@code SECONDARY} road does not
 * block a placement (and once a multi-building is accepted, TLC additionally lets it
 * suppress the road class through {@code getEffectivePlannedRoadType}'s
 * {@code overridden} term). Citylines requires both classes to win over the whole footprint,
 * so the decision has to be taken here, before {@code placeBuilding} writes the
 * {@code buildingGrid}.
 *
 * <p>This injection only covers Lost Cities' own placement path. LC2H's fast
 * multi-chunk planner cancels {@code calculateBuildings} and never calls
 * {@code canPlaceBuilding}, which is why {@code BuildingInfoMixin} additionally
 * refuses at the consumption site ({@code initMultiBuildingSection}).
 *
 * <p>For a non-Citylines dimension nothing happens: {@code CitylinesRoadState.of} returns
 * {@code null} and the original method runs untouched.
 */
@Mixin(value = MultiChunk.class, remap = false)
public abstract class MultiChunkMixin {

    @Inject(method = "canPlaceBuilding", at = @At("HEAD"), cancellable = true)
    private void citylines$refuseReservedRoadFootprint(ChunkCoord topleft, IDimensionInfo provider, LostCityProfile profile,
                                                 CityStyle buildingCityStyle, MultiBuilding building, int cityLevel,
                                                 int maxCellars, int x, int z,
                                                 CallbackInfoReturnable<Boolean> cir) {
        CitylinesRoadState state = CitylinesRoadState.of(provider);
        if (state == null || !state.isActive()) {
            return;
        }
        ChunkCoord buildingTopLeft = topleft.offset(x, z);
        if (V3RoadRights.footprintTouchesReservedRoad(state, buildingTopLeft, building.getDimX(),
                building.getDimZ())) {
            RoadDiagnostics.recordMultiCandidateRefused(state, buildingTopLeft, building.getDimX(),
                    building.getDimZ());
            cir.setReturnValue(false);
        }
    }
}
