package com.scarasol.citylines.mixin.ec;

import com.scarasol.citylines.compat.ExtractionCitiesDomainCheck;
import com.scarasol.citylines.terrain.ExtractionCitiesMutationAdapter;
import com.scarasol.extractioncities.server.level.DynamicDimensionManager;
import com.scarasol.extractioncities.world.level.dimension.DynamicDimensionRecord;
import com.scarasol.extractioncities.world.level.dimension.LostCityBuildingOverride;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = DynamicDimensionManager.class, remap = false)
public abstract class ExtractionCitiesOverridesMixin implements ExtractionCitiesMutationAdapter {
    @Inject(method = "setGenerateLostCities", at = @At("HEAD"), require = 1)
    private static void citylines$freezeEnabled(MinecraftServer server, ResourceLocation dimension,
                                                boolean enabled,
                                                CallbackInfoReturnable<DynamicDimensionRecord> callback) {
        ExtractionCitiesDomainCheck.beforeRuleUpdate(server, dimension, enabled, "enabled");
    }

    @Inject(method = "setLostCitiesProfile", at = @At("HEAD"), require = 1)
    private static void citylines$freezeProfile(MinecraftServer server, ResourceLocation dimension,
                                                String profile,
                                                CallbackInfoReturnable<DynamicDimensionRecord> callback) {
        ExtractionCitiesDomainCheck.beforeRuleUpdate(server, dimension, profile.trim(), "profile");
    }

    @Inject(method = "setLostCitiesWorldStyle", at = @At("HEAD"), require = 1)
    private static void citylines$freezeWorldStyle(MinecraftServer server, ResourceLocation dimension,
                                                   String worldStyle,
                                                   CallbackInfoReturnable<DynamicDimensionRecord> callback) {
        ExtractionCitiesDomainCheck.beforeRuleUpdate(server, dimension, worldStyle.trim(), "worldstyle");
    }

    @Inject(method = "setLostCityBuildingOverrides", at = @At("HEAD"), require = 1)
    private static void citylines$validate(MinecraftServer server, ResourceLocation dimension,
                                          List<LostCityBuildingOverride> overrides,
                                          CallbackInfoReturnable<DynamicDimensionRecord> callback) {
        ExtractionCitiesDomainCheck.beforeUpdate(server, dimension, overrides);
    }

    @Inject(method = "setLostCityBuildingOverrides", at = @At("RETURN"), require = 1)
    private static void citylines$committed(MinecraftServer server, ResourceLocation dimension,
                                           List<LostCityBuildingOverride> overrides,
                                           CallbackInfoReturnable<DynamicDimensionRecord> callback) {
        ExtractionCitiesDomainCheck.afterUpdate(server, callback.getReturnValue());
    }
}
