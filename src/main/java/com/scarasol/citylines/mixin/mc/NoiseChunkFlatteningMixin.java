package com.scarasol.citylines.mixin.mc;

import com.scarasol.citylines.terrain.TerrainFlattening;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The terrain override itself: the single per-block funnel of the vanilla noise pipeline.
 *
 * <p>{@code NoiseChunk.getInterpolatedState()} is called once per block by the chunk filler and once
 * per block by the height sampler, and by nothing else in vanilla (verified by scanning the whole
 * decompiled jar). That makes it the one place where "generate the terrain flat" can be expressed
 * without duplicating any of Lost Cities' or vanilla's worldgen logic.
 *
 * <p>Two injections, because the two halves of the job have different costs:
 * <ul>
 *   <li>{@code HEAD}: above the target ground the answer is always air, so the original computation
 *       (aquifer, ore veins) is skipped. Vanilla's filler does not even write air blocks, so those
 *       blocks cost nothing downstream either.</li>
 *   <li>{@code RETURN}: at or below the target ground, air is replaced by the dimension's default
 *       block. Everything else — stone, deepslate, water, lava, ores — is returned untouched, which
 *       is what keeps the underground intact.</li>
 * </ul>
 *
 * <p>When no flattening applies (feature disabled, chunk outside a city, water-guarded cell, other
 * landscape type, or a height-sampling pass) both hooks return {@code null} and the method behaves
 * exactly like vanilla.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkFlatteningMixin {

    @Inject(method = "getInterpolatedState", at = @At("HEAD"), cancellable = true)
    private void citylines$flattenAbove(CallbackInfoReturnable<BlockState> cir) {
        BlockState forced = TerrainFlattening.overrideHead((NoiseChunk) (Object) this);
        if (forced != null) {
            cir.setReturnValue(forced);
        }
    }

    @Inject(method = "getInterpolatedState", at = @At("RETURN"), cancellable = true)
    private void citylines$flattenBelow(CallbackInfoReturnable<BlockState> cir) {
        BlockState forced = TerrainFlattening.overrideReturn((NoiseChunk) (Object) this,
                cir.getReturnValue());
        if (forced != null) {
            cir.setReturnValue(forced);
        }
    }
}
