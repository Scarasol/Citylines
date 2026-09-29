package com.scarasol.citylines.mixin.tlc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.citylines.terrain.TerrainFlattening;
import mcjty.lostcities.setup.ForgeEventHandlers;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityFeature;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Bind after TLC initializes its current modes, but before its optional spawn search generates chunks. */
@Mixin(value = ForgeEventHandlers.class, remap = false)
public abstract class SpawnBindingMixin {
    @WrapOperation(method = "onCreateSpawnPoint", at = @At(value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/LostCityFeature;getDimensionInfo(Lnet/minecraft/world/level/WorldGenLevel;)Lmcjty/lostcities/worldgen/IDimensionInfo;"),
            require = 1, allow = 1)
    private IDimensionInfo citylines$prepareGeneration(LostCityFeature feature, WorldGenLevel world,
                                                       Operation<IDimensionInfo> original) {
        IDimensionInfo provider = original.call(feature, world);
        if (world instanceof ServerLevel level) TerrainFlattening.onSpawnProvider(level, provider);
        return provider;
    }
}
