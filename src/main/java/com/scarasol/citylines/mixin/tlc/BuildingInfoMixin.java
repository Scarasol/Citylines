package com.scarasol.citylines.mixin.tlc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.citylines.road.tlc.CitylinesRoadState;
import com.scarasol.citylines.road.tlc.V3RoadRights;
import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.cityassets.BuildingPart;
import mcjty.lostcities.worldgen.street.PlannedRoadType;
import mcjty.lostcities.worldgen.street.PlannedStreetInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Lost Cities' final road-class decision Citylines-aware, and refuses multi-buildings
 * that would be swallowed by a Citylines road.
 *
 * <h2>Why only the {@code EffectiveStreetResolver.resolve} calls</h2>
 *
 * {@code BuildingInfo.getEffectivePlannedRoadType} decides whether a raw planned road
 * survives. It has exactly two decisions: a precedence test
 * ({@code isCity}, predefined building/street, accepted multi-building) which is
 * supplied by the caller and must stay authoritative, and one neighbour heuristic
 * ("the chunk is a city chunk and at least one adjacent <em>raw</em> city chunk with
 * the same profile exists, in a direction the raw street connects to"). The second
 * one is a TLC assumption that a connectivity-first network must not inherit: Citylines
 * components are allowed to end at a city-mask edge, a water shore or a different
 * profile.
 *
 * <p>So the minimal correct seam is the two calls to
 * {@link mcjty.lostcities.worldgen.street.EffectiveStreetResolver}: wrapping them
 * lets us keep 100% of the precedence logic, including every veto that lives in the
 * enclosing method, and replace only the neighbour input with the Citylines double-sided
 * mask ({@code V3CellInfo.edge}). The rest of the method still runs unchanged,
 * which also keeps its side effects (profiles and raw city lookups fill Lost Cities'
 * caches) identical to TLC for the parts that are not Citylines.
 *
 * <p>The wrapped value is only replaced when {@link CitylinesRoadState#isManaged} is true;
 * otherwise {@code original.call(...)} runs with the exact captured arguments, so a
 * dimension without Citylines keeps byte-identical behaviour.
 *
 * <p>The second injection refuses a multi-building at its consumption site: the
 * section Lost Cities accepted is turned back into a single section when its whole
 * footprint overlaps a reserved Citylines road. {@code MultiChunk.MB}/{@code buildingGrid}
 * are package private, so a cross-package mixin can neither read nor fix the recorded
 * grid, and LC2H's fast planner never even calls {@code MultiChunk.canPlaceBuilding}.
 * Refusing here works on both paths; the accompanying {@code MultiChunkMixin} refuses
 * earlier (before the grid is written) on the native path.
 */
@Mixin(value = BuildingInfo.class, remap = false)
public abstract class BuildingInfoMixin {

    /**
     * The early-return call: {@code resolve(rawStreet.roadType(), characteristics.isCity, false, false)}.
     * In Citylines the same replacement is applied; it is a no-op in practice because the
     * enclosing branch already guarantees {@code !isCity || roadType == NONE}.
     */
    @WrapOperation(method = "getEffectivePlannedRoadType",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lmcjty/lostcities/worldgen/street/EffectiveStreetResolver;resolve(Lmcjty/lostcities/worldgen/street/PlannedRoadType;ZZZ)Lmcjty/lostcities/worldgen/street/PlannedRoadType;"))
    private static PlannedRoadType citylines$roadRoadTypeNotCityOrNotRoad(PlannedRoadType rawRoadType,
                                                                       boolean currentChunkIsCity,
                                                                       boolean hasConnectedCityNeighbor,
                                                                       boolean overriddenByHigherPrecedenceContent,
                                                                       Operation<PlannedRoadType> original,
                                                                       ChunkCoord coord, IDimensionInfo provider,
                                                                       LostCityProfile profile,
                                                                       LostChunkCharacteristics characteristics,
                                                                       PlannedStreetInfo rawStreet) {
        return citylines$resolve(original, coord, provider, rawRoadType, currentChunkIsCity, hasConnectedCityNeighbor,
                overriddenByHigherPrecedenceContent);
    }

    /**
     * The main call: {@code resolve(rawStreet.roadType(), characteristics.isCity, connectedCityNeighbor, overridden)}.
     * This is where the TLC neighbour heuristic is replaced by the Citylines double-sided mask.
     */
    @WrapOperation(method = "getEffectivePlannedRoadType",
            at = @At(value = "INVOKE", ordinal = 1,
                    target = "Lmcjty/lostcities/worldgen/street/EffectiveStreetResolver;resolve(Lmcjty/lostcities/worldgen/street/PlannedRoadType;ZZZ)Lmcjty/lostcities/worldgen/street/PlannedRoadType;"))
    private static PlannedRoadType citylines$roadRoadTypeConnectedNeighbour(PlannedRoadType rawRoadType,
                                                                         boolean currentChunkIsCity,
                                                                         boolean hasConnectedCityNeighbor,
                                                                         boolean overriddenByHigherPrecedenceContent,
                                                                         Operation<PlannedRoadType> original,
                                                                         ChunkCoord coord, IDimensionInfo provider,
                                                                         LostCityProfile profile,
                                                                         LostChunkCharacteristics characteristics,
                                                                         PlannedStreetInfo rawStreet) {
        return citylines$resolve(original, coord, provider, rawRoadType, currentChunkIsCity, hasConnectedCityNeighbor,
                overriddenByHigherPrecedenceContent);
    }

    /**
     * Citylines dimensions answer with the planner's double-sided mask; everything else keeps
     * the original call with its exact captured arguments. The four arguments below
     * are the ones Lost Cities itself supplies, so every veto it computed (city
     * membership, predefined content, accepted multi-building) is preserved.
     */
    @Unique
    private static PlannedRoadType citylines$resolve(Operation<PlannedRoadType> original, ChunkCoord coord,
                                                     IDimensionInfo provider, PlannedRoadType rawRoadType,
                                                     boolean currentChunkIsCity, boolean hasConnectedCityNeighbor,
                                                     boolean overriddenByHigherPrecedenceContent) {
        CitylinesRoadState state = CitylinesRoadState.of(provider);
        if (state == null || !state.isActive()) {
            return original.call(rawRoadType, currentChunkIsCity, hasConnectedCityNeighbor,
                    overriddenByHigherPrecedenceContent);
        }
        return V3RoadRights.effectiveRoadType(state, coord, rawRoadType, currentChunkIsCity, hasConnectedCityNeighbor,
                overriddenByHigherPrecedenceContent);
    }

    /**
     * Consumption-site veto for multi-buildings. Runs for every {@code return} of
     * {@code initMultiBuildingSection} (predefined section, no building, and the
     * automatic section); {@code V3RoadRights.refuseMultiBuildingOnReservedRoad} ignores
     * everything except an accepted automatic multi section, so a predefined
     * multi-building keeps TLC's precedence over the automatic street field.
     */
    @Inject(method = "initMultiBuildingSection", at = @At("RETURN"))
    private static void citylines$refuseMultiBuildingOnReservedRoad(LostChunkCharacteristics characteristics, ChunkCoord coord,
                                                              IDimensionInfo provider, LostCityProfile profile,
                                                              CallbackInfo ci) {
        V3RoadRights.refuseMultiBuildingOnReservedRoad(characteristics, coord, provider);
    }

    /**
     * In a Citylines dimension the bridge part is the Citylines deck, whatever the CityStyle
     * selector picked.
     *
     * <p>Lost Cities resolves the deck as
     * {@code endpoint.largeBridgeType != null ? largeBridgeType : bridgeType}, both of
     * which come from the shore's CityStyle {@code largebridges} / {@code bridges}
     * selectors. CityStyle inheritance is additive
     * ({@code largeBridgeSelector.addAll(inheritFrom...)}) and
     * {@code citylines:standard} inherits {@code lostcities:citystyle_common} with
     * its 14-wide TLC {@code bridge_large_open}, so the selector can legitimately pick
     * the TLC deck; it cannot be removed from the child. Substituting the part here
     * (presence unchanged: non-null stays non-null, null stays null) makes every
     * consumer — the deck renderer, the support/connector strips and the scattered
     * decoration veto — see one consistent Citylines deck. A dimension without Citylines, or a
     * dimension where the deck asset does not resolve, keeps the original part.
     */
    @Inject(method = "hasXBridge", at = @At("RETURN"), cancellable = true)
    private void citylines$roadDeckX(IDimensionInfo provider, CallbackInfoReturnable<BuildingPart> cir) {
        BuildingPart deck = citylines$roadDeck(provider, cir.getReturnValue());
        if (deck != null) {
            cir.setReturnValue(deck);
        }
    }

    @Inject(method = "hasZBridge", at = @At("RETURN"), cancellable = true)
    private void citylines$roadDeckZ(IDimensionInfo provider, CallbackInfoReturnable<BuildingPart> cir) {
        BuildingPart deck = citylines$roadDeck(provider, cir.getReturnValue());
        if (deck != null) {
            cir.setReturnValue(deck);
        }
    }

    @Unique
    private static BuildingPart citylines$roadDeck(IDimensionInfo provider, BuildingPart current) {
        if (current == null) {
            return null;
        }
        CitylinesRoadState state = CitylinesRoadState.of(provider);
        if (state == null || !state.isActive()) {
            return null;
        }
        return state.bridgeDeckPart();
    }
}
