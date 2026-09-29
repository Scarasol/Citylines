package com.scarasol.citylines.mixin.tlc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.citylines.CitylinesMod;
import com.scarasol.citylines.road.tlc.CitylinesRoadState;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.DefaultDimensionInfo;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/**
 * Owns the per-dimension Citylines road context: which world style the dimension resolves
 * and whether Citylines runs at all.
 *
 * <p>The world style must be substituted <b>inside</b> the constructor, because the
 * constructor resolves {@code AssetRegistries.WORLDSTYLES.get(world, profile.getWorldStyle())}
 * before it creates the street planner the Citylines state is attached to. The wrap therefore
 * decides from the constructor's own arguments only (see
 * {@link CitylinesRoadState#decideForConstructor}) and never from the partially
 * constructed {@code IDimensionInfo}, whose LC2H-overwritten, ThreadLocal-backed
 * getters are not primed at that point. A dimension that is not a Citylines candidate, or
 * whose Citylines chain does not resolve, keeps the original style string and is
 * bit-identical to TLC.
 *
 * <p>The decision object computed in the wrap is carried to the constructor tail in a
 * {@code @Unique} field and handed to
 * {@link CitylinesRoadState#attach(IDimensionInfo, WorldGenLevel, LostCityProfile, CitylinesRoadState.ConstructorDecision)}.
 * Computing it once — and reusing it verbatim — is what makes it impossible for a
 * dimension to keep the Citylines world style while Citylines is refused for it: the style and the
 * activation verdict are two fields of one decision, not two evaluations.
 */
@Mixin(value = DefaultDimensionInfo.class, remap = false)
public abstract class DefaultDimensionInfoMixin {

    @Unique
    private CitylinesRoadState.ConstructorDecision citylines$constructorDecision;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lmcjty/lostcities/config/LostCityProfile;getWorldStyle()Ljava/lang/String;"))
    private String citylines$roadWorldStyle(LostCityProfile instance, Operation<String> original,
                                          WorldGenLevel world, LostCityProfile profile,
                                          LostCityProfile profileOutside) {
        String originalStyle = original.call(instance);
        // One evaluation for both the style and the activation verdict; the tail
        // injection consumes exactly this object.
        CitylinesRoadState.ConstructorDecision decision =
                CitylinesRoadState.decideForConstructor(world, profile, originalStyle);
        this.citylines$constructorDecision = decision;
        String effective = decision.worldStyle();
        String dimension = world.getLevel().dimension().location().toString();
        if (Objects.equals(effective, originalStyle)) {
            // One line per dimension, so a run says unambiguously which branch was taken.
            CitylinesMod.LOGGER.info("[citylines] world style {} kept for {} (Citylines not eligible or Citylines assets unavailable)",
                    originalStyle, dimension);
        } else {
            CitylinesMod.LOGGER.info("[citylines] Citylines world style {} for {} (was {})",
                    effective, dimension, originalStyle);
        }
        return effective;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void citylines$onDimensionInfoCreated(WorldGenLevel world, LostCityProfile profile,
                                                  LostCityProfile profileOutside, CallbackInfo ci) {
        CitylinesRoadState.ConstructorDecision decision = this.citylines$constructorDecision;
        this.citylines$constructorDecision = null;
        if (decision == null) {
            // Defensive: the wrap operation above is required (require = 1) and always
            // runs, but a decision must exist for the tail even if it somehow did not.
            decision = CitylinesRoadState.decideForConstructor(world, profile,
                    profile == null ? null : profile.getWorldStyle());
        }
        CitylinesRoadState.attach((IDimensionInfo) (Object) this, world, profile, decision);
    }
}
