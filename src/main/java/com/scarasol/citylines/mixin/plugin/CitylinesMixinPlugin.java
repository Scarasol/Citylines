package com.scarasol.citylines.mixin.plugin;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Gates optional adapters before target resolution, using Forge mod discovery rather than reflection. */
public final class CitylinesMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("citylines-mixin-plugin");

    /** Mod id of LC2H, as declared by its {@code mods.toml}. */
    private static final String LC2H_MOD_ID = "lc2h";
    /** Marker for every mixin in the optional LC2H sub-package. */
    private static final String LC2H_MIXIN_MARKER = ".mixin.lc2h.";
    /**
     * The native-body city height gate: it injects into {@code City.getCityFactor}, which LC2H replaces
     * wholesale with {@code @Overwrite}. With LC2H installed the target call sites do not exist, so the
     * mixin must not be applied at all — the LC2H adapter in the {@code lc2h} sub-package takes over.
     */
    private static final String NATIVE_CITY_GATE_MIXIN = ".mixin.tlc.CityHeightGateMixin";

    private static final AtomicBoolean DECISION_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SELECTOR_REGISTERED = new AtomicBoolean();

    @Override
    public void onLoad(String mixinPackage) {
        if (SELECTOR_REGISTERED.compareAndSet(false, true)) {
            org.spongepowered.asm.mixin.injection.selectors.TargetSelector.register(
                    Lc2hTerrainSelector.class, "citylines");
        }
        LOGGER.info("[citylines] mixin config plugin active for package '{}'", mixinPackage);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName == null) {
            return true;
        }
        if (mixinClassName.contains(".mixin.ec.")) {
            return detectMod("extractioncities").loaded();
        }
        if (mixinClassName.endsWith(".tlc.BuildingInfoCityLevelMixin")) {
            return !detectLc2h().loaded();
        }
        if (mixinClassName.contains(NATIVE_CITY_GATE_MIXIN)) {
            // Inverse gate: only meaningful when LC2H did NOT overwrite City.getCityFactor.
            boolean apply = !detectLc2h().loaded();
            if (DECISION_LOGGED.compareAndSet(false, true)) {
                LOGGER.info("[citylines] native city height gate -> {} (target {}, lc2h loaded={})",
                        apply ? "APPLIED" : "SKIPPED (LC2H owns getCityFactor)", targetClassName,
                        !apply);
            }
            return apply;
        }
        if (!mixinClassName.contains(LC2H_MIXIN_MARKER)) {
            // Every Lost Cities mixin (and anything else) always applies.
            return true;
        }
        Detection detection = detectLc2h();
        if (DECISION_LOGGED.compareAndSet(false, true)) {
            // One line, so a server log proves which source answered and what it saw.
            LOGGER.info("[citylines] optional LC2H mixin {} -> {} (target {}, lc2h loaded={}, source={})",
                    mixinClassName, detection.loaded() ? "APPLIED" : "SKIPPED", targetClassName,
                    detection.loaded(), detection.source());
        }
        return detection.loaded();
    }

    /** The mod-list answer plus the source that produced it (for the one-shot log). */
    private record Detection(boolean loaded, String source) {
    }

    /**
     * LC2H presence from Forge's mod lists. The full {@link ModList} is preferred;
     * during this early phase it may not exist yet, in which case the discovery-time
     * {@link LoadingModList} (already populated when mod mixin configs are registered)
     * answers. No reflection, no class-path probing.
     */
    private static Detection detectLc2h() {
        return detectMod(LC2H_MOD_ID);
    }

    private static Detection detectMod(String modId) {
        ModList modList = ModList.get();
        if (modList != null) {
            return new Detection(modList.isLoaded(modId), "ModList");
        }
        LoadingModList loading = LoadingModList.get();
        if (loading != null) {
            return new Detection(loading.getModFileById(modId) != null, "LoadingModList");
        }
        // Neither list exists: skip the optional mixin (the safe direction).
        return new Detection(false, "none");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    /** {@code null} = use the {@code mixins} list from {@code citylines.mixins.json}. */
    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
