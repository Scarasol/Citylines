package com.scarasol.citylines.mixin.tlc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.citylines.terrain.ChunkHeightmapRepair;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "mcjty.lostcities.worldgen.ChunkDriver$SectionCache", remap = false)
public abstract class SectionCacheHeightmapMixin {
    @WrapOperation(method = "generate", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1, allow = 1)
    private BlockState citylines$trackActualWrite(LevelChunkSection section, int x, int y, int z, BlockState state,
                                                 boolean lock, Operation<BlockState> original) {
        BlockState old = original.call(section, x, y, z, state, lock);
        if (old != state) ChunkHeightmapRepair.changed(x, z);
        return old;
    }
}
