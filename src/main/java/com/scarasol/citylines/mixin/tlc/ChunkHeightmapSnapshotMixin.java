package com.scarasol.citylines.mixin.tlc;

import com.scarasol.citylines.terrain.RawHeightSnapshot;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the frozen raw height to Lost Cities' cached chunk heightmap and carries it through the copy
 * constructor.
 *
 * <p>Lost Cities fills a whole height-sampling group with {@code new ChunkHeightmap(other)}, so copying
 * the snapshot is what makes the raw value reach every chunk of the group instead of only the chunk
 * whose generation happened to trigger the sample.
 */
@Mixin(value = ChunkHeightmap.class, remap = false)
public abstract class ChunkHeightmapSnapshotMixin implements RawHeightSnapshot {

    @Unique
    private int citylines$rawHeightValue = RawHeightSnapshot.NOT_SAMPLED;

    @Override
    public int citylines$rawHeight() {
        return citylines$rawHeightValue;
    }

    @Override
    public void citylines$rawHeight(int height) {
        this.citylines$rawHeightValue = height;
    }

    @Inject(method = "<init>(Lmcjty/lostcities/worldgen/ChunkHeightmap;)V", at = @At("RETURN"),
            require = 1)
    private void citylines$copyRawHeight(ChunkHeightmap other, CallbackInfo ci) {
        if (other instanceof RawHeightSnapshot holder) {
            this.citylines$rawHeightValue = holder.citylines$rawHeight();
        }
    }
}
