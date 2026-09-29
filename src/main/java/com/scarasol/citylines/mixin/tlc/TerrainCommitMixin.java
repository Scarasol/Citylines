package com.scarasol.citylines.mixin.tlc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.citylines.terrain.ChunkHeightmapRepair;
import com.scarasol.citylines.terrain.TerrainOwnership;
import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class TerrainCommitMixin {
    @Shadow public IDimensionInfo provider;

    @WrapOperation(method = {"generate", "generateBuilding"}, at = @At(value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/ChunkDriver;actuallyGenerate(Lnet/minecraft/world/level/chunk/ChunkAccess;)V"),
            require = 2, allow = 2)
    private void citylines$commit(ChunkDriver driver, ChunkAccess chunk, Operation<Void> original) {
        if (TerrainOwnership.isManaged(provider)) {
            ChunkHeightmapRepair.commit(driver, chunk, () -> original.call(driver, chunk));
        } else {
            original.call(driver, chunk);
        }
    }
}
