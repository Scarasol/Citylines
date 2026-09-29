package com.scarasol.citylines.mixin.plugin;

import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.selectors.ElementNode;
import org.spongepowered.asm.mixin.injection.selectors.ISelectorContext;
import org.spongepowered.asm.mixin.injection.selectors.ITargetSelector;
import org.spongepowered.asm.mixin.injection.selectors.ITargetSelectorDynamic;
import org.spongepowered.asm.mixin.injection.selectors.MatchResult;
import org.spongepowered.asm.mixin.transformer.MixinTargetContext;

/**
 * Mixin 0.8.5 filters merged methods when a selector's max count exceeds one.
 * Select exactly one method by its unique instruction, never by its generated handler name.
 * This is read-only class-transform work, not a world-generation lookup.
 */
@ITargetSelectorDynamic.SelectorId(namespace = "citylines", value = "terrain")
public final class Lc2hTerrainSelector implements ITargetSelectorDynamic {
    static final String TARGET = "mcjty/lostcities/worldgen/LostCityTerrainFeature";
    static final String GPU = "org/admany/lc2h/worldgen/gpu/TerrainCorrectionGpuPipeline";
    static final String GPU_DESC = "(Lnet/minecraft/world/level/WorldGenLevel;"
            + "Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/ChunkHeightmap;"
            + "Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/worldgen/ChunkDriver;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)Z";
    private final MethodNode method;

    private Lc2hTerrainSelector(MethodNode method) {
        this.method = method;
    }

    public static ITargetSelectorDynamic parse(String argument, ISelectorContext context) {
        if (!(context.getMixin() instanceof MixinTargetContext target)) {
            throw new IllegalStateException("Citylines terrain selector requires a Mixin target context");
        }
        MethodNode selected = select(target.getTargetClassNode(), argument);
        LogManager.getLogger("citylines-mixin-plugin").info(
                "[citylines] terrain selector {} -> {}{} (one exact instruction)",
                argument, selected.name, selected.desc);
        return new Lc2hTerrainSelector(selected);
    }

    static MethodNode select(ClassNode target, String argument) {
        if (!TARGET.equals(target.name)) {
            throw new IllegalStateException("Citylines terrain selector refused owner " + target.name);
        }
        String field = switch (argument) {
            case "natural" -> "LC2H_PRESERVE_NATURAL_MOUNTAINS";
            case "interior" -> "LC2H_SKIP_INTERIOR_TERRAIN_CORRECTION";
            case "gpu" -> null;
            default -> throw new IllegalArgumentException("Unknown Citylines terrain selector: " + argument);
        };
        MethodNode found = null;
        int hits = 0;
        for (MethodNode candidate : target.methods) {
            for (AbstractInsnNode instruction : candidate.instructions) {
                boolean match = field != null
                        ? instruction instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC
                            && TARGET.equals(read.owner) && field.equals(read.name) && "Z".equals(read.desc)
                        : instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                            && GPU.equals(call.owner) && "tryCorrect".equals(call.name) && GPU_DESC.equals(call.desc);
                if (match) {
                    hits++;
                    found = candidate;
                }
            }
        }
        if (hits != 1 || (found.access & Opcodes.ACC_STATIC) != 0) {
            throw new IllegalStateException("Citylines LC2H terrain contract changed: " + argument
                    + " requires one instruction in an instance method, found " + hits);
        }
        return found;
    }

    @Override public ITargetSelector next() { return null; }
    @Override public ITargetSelector configure(Configure request, String... args) { return this; }
    @Override public ITargetSelector validate() { return this; }
    @Override public ITargetSelector attach(ISelectorContext context) { return this; }
    @Override public int getMinMatchCount() { return 1; }
    @Override public int getMaxMatchCount() { return 1; }
    @Override public <TNode> MatchResult match(ElementNode<TNode> node) {
        return node.getMethod() == method ? MatchResult.EXACT_MATCH : MatchResult.NONE;
    }
}
