package com.scarasol.citylines.mixin.tlc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.citylines.terrain.ChunkHeightmapRepair;
import mcjty.lostcities.worldgen.ChunkDriver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ChunkDriver.class, remap = false)
public abstract class ChunkDriverHeightmapMixin {
    @WrapOperation(method = "actuallyGenerate", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/level/levelgen/Heightmap;update(IIILnet/minecraft/world/level/block/state/BlockState;)Z"),
            require = 4, allow = 4)
    private boolean citylines$skipSyntheticTop(Heightmap heightmap, int x, int y, int z, BlockState state,
                                              Operation<Boolean> original, ChunkAccess chunk) {
        return !ChunkHeightmapRepair.owns(chunk) && original.call(heightmap, x, y, z, state);
    }
}
