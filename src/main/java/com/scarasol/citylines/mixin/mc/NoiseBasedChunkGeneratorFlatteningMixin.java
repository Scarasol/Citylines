package com.scarasol.citylines.mixin.mc;

import com.scarasol.citylines.terrain.TerrainFlattening;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.OptionalInt;
import java.util.function.Predicate;

/**
 * Context plumbing for {@link NoiseChunkFlatteningMixin}: tells the override which dimension is
 * being generated, and which of the two call paths it is on.
 *
 * <p>{@code NoiseChunk} itself carries no level reference — only coordinates and noise settings — so
 * the dimension has to come from outside. These two methods are the right place because both are
 * instance methods of the <b>per-dimension</b> generator instance: {@code this} identifies the
 * dimension, so no {@code ServerLevel} has to be threaded through vanilla's signatures.
 *
 * <ul>
 *   <li>{@code doFill} — a chunk is being filled: push a frame that <b>allows</b> the override.
 *       This has to be {@code doFill} and not {@code fillFromNoise}: the latter hands the actual
 *       per-block work to {@code Util.backgroundExecutor()} and returns a future, so a frame pushed
 *       there lives on the calling thread while every {@code getInterpolatedState} call happens on
 *       a pool thread (measured: 2.46M override calls with no frame at all).</li>
 *   <li>{@code iterateNoiseColumn} — the column is only being sampled for its height, which is what
 *       Lost Cities' chunk heightmaps and the city eligibility gates go through: push a
 *       frame that <b>disables</b> the override, so every measurement sees raw terrain instead of
 *       the flattened result. Without that flag the city decision would feed back into itself.</li>
 * </ul>
 *
 * <p>Frames are pushed and popped around the call and chain through a saved previous frame, so a
 * sampling pass started from inside a fill (as the city mask does) cannot leak
 * its flag into the surrounding fill.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorFlatteningMixin {

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;doFill(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
            require = 1, allow = 1)
    private ChunkAccess citylines$fill(NoiseBasedChunkGenerator generator, Blender blender,
                                      StructureManager structures, RandomState random, ChunkAccess chunk,
                                      int cellsX, int cellsZ, Operation<ChunkAccess> original) {
        return TerrainFlattening.withContext(generator, false,
                () -> original.call(generator, blender, structures, random, chunk, cellsX, cellsZ));
    }

    @WrapOperation(method = {"getBaseHeight", "getBaseColumn"}, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;iterateNoiseColumn(Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;IILorg/apache/commons/lang3/mutable/MutableObject;Ljava/util/function/Predicate;)Ljava/util/OptionalInt;"),
            require = 2, allow = 2)
    private OptionalInt citylines$sample(NoiseBasedChunkGenerator generator, LevelHeightAccessor height,
                                         RandomState random, int x, int z, MutableObject<NoiseColumn> column,
                                         Predicate<BlockState> predicate, Operation<OptionalInt> original) {
        return TerrainFlattening.withContext(generator, true,
                () -> original.call(generator, height, random, x, z, column, predicate));
    }
}
