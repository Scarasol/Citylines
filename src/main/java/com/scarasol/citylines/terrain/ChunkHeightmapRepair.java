package com.scarasol.citylines.terrain;

import com.scarasol.citylines.mixin.mc.HeightmapAccess;
import mcjty.lostcities.worldgen.ChunkDriver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;
import java.util.function.IntUnaryOperator;

/** Scoped to one synchronous TLC commit; no world-wide dirty registry or neighbor loading. */
public final class ChunkHeightmapRepair {
    private static final ThreadLocal<Commit> CURRENT = new ThreadLocal<>();
    private static final Heightmap.Types[] TYPES = {Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES};

    private ChunkHeightmapRepair() {
    }

    public static void commit(ChunkDriver driver, ChunkAccess chunk, Runnable write) {
        if (driver.getPrimer() != chunk) {
            throw new IllegalStateException("TLC heightmap target differs from its actual write chunk");
        }
        EnumSet<Heightmap.Types> missing = EnumSet.noneOf(Heightmap.Types.class);
        for (Heightmap.Types type : TYPES) {
            if (!chunk.hasPrimedHeightmap(type)) missing.add(type);
        }
        if (!missing.isEmpty()) Heightmap.primeHeightmaps(chunk, missing);
        Commit previous = CURRENT.get();
        Commit current = new Commit(chunk);
        CURRENT.set(current);
        try {
            write.run();
            current.rebuild();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static boolean owns(ChunkAccess chunk) {
        Commit current = CURRENT.get();
        return current != null && current.chunk == chunk;
    }

    public static void changed(int x, int z) {
        Commit current = CURRENT.get();
        if (current != null) {
            int column = (z << 4) | x;
            current.dirty[column >>> 6] |= 1L << (column & 63);
        }
    }

    private static final class Commit implements IntUnaryOperator {
        final ChunkAccess chunk;
        final long[] dirty = new long[4];
        int x;
        int z;

        Commit(ChunkAccess chunk) {
            this.chunk = chunk;
        }

        void rebuild() {
            int minY = chunk.getMinBuildHeight();
            int topY = Math.min(chunk.getMaxBuildHeight() - 1, chunk.getHighestSectionPosition() + 15);
            Heightmap[] maps = new Heightmap[4];
            for (int i = 0; i < maps.length; i++) maps[i] = chunk.getOrCreateHeightmapUnprimed(TYPES[i]);
            int[] heights = new int[4];
            for (int word = 0; word < dirty.length; word++) {
                for (long bits = dirty[word]; bits != 0; bits &= bits - 1) {
                    int column = (word << 6) | Long.numberOfTrailingZeros(bits);
                    x = column & 15;
                    z = column >>> 4;
                    ColumnHeights.scan(minY, topY, this, heights);
                    for (int i = 0; i < maps.length; i++) {
                        ((HeightmapAccess) (Object) maps[i]).citylines$setHeight(x, z, heights[i]);
                    }
                }
            }
        }

        @Override
        public int applyAsInt(int y) {
            BlockState state = chunk.getSection(chunk.getSectionIndex(y)).getBlockState(x, y & 15, z);
            int mask = 0;
            for (int i = 0; i < TYPES.length; i++) {
                if (TYPES[i].isOpaque().test(state)) mask |= 1 << i;
            }
            return mask;
        }
    }
}
