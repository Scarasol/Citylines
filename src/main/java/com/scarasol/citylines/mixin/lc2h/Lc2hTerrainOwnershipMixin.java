package com.scarasol.citylines.mixin.lc2h;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.citylines.terrain.TerrainOwnership;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** TLC alone owns the managed dimension's city-edge correction; LC2H's globals stay untouched. */
@Mixin(targets = "mcjty.lostcities.worldgen.LostCityTerrainFeature", remap = false, priority = 1500)
public abstract class Lc2hTerrainOwnershipMixin {
    @Shadow public IDimensionInfo provider;

    @WrapOperation(method = "@citylines:terrain(natural)", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lmcjty/lostcities/worldgen/LostCityTerrainFeature;LC2H_PRESERVE_NATURAL_MOUNTAINS:Z"),
            require = 1, allow = 1)
    private boolean citylines$naturalProtection(Operation<Boolean> original) {
        return !TerrainOwnership.isManaged(provider) && original.call();
    }

    @WrapOperation(method = "@citylines:terrain(interior)", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lmcjty/lostcities/worldgen/LostCityTerrainFeature;LC2H_SKIP_INTERIOR_TERRAIN_CORRECTION:Z"),
            require = 1, allow = 1)
    private boolean citylines$interiorCorrection(Operation<Boolean> original) {
        return !TerrainOwnership.isManaged(provider) && original.call();
    }

    @WrapOperation(method = "@citylines:terrain(gpu)", at = @At(value = "INVOKE",
            target = "Lorg/admany/lc2h/worldgen/gpu/TerrainCorrectionGpuPipeline;tryCorrect(Lnet/minecraft/world/level/WorldGenLevel;Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/ChunkHeightmap;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/worldgen/ChunkDriver;Lnet/minecraft/world/level/block/state/BlockState;)Z"),
            require = 1, allow = 1)
    private boolean citylines$terrainCorrection(WorldGenLevel region, ChunkCoord coord, ChunkHeightmap heightmap,
                                                IDimensionInfo dimension, ChunkDriver driver, BlockState air,
                                                Operation<Boolean> original) {
        return !TerrainOwnership.isManaged(dimension)
                && original.call(region, coord, heightmap, dimension, driver, air);
    }
}
