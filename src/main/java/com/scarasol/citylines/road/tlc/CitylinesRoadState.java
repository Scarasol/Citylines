package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.CitylinesMod;
import com.scarasol.citylines.compat.Lc2hStatus;
import com.scarasol.citylines.compat.ModCompat;
import com.scarasol.citylines.config.CitylinesConfig;
import com.scarasol.citylines.terrain.TerrainOwnership;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import com.scarasol.citylines.road.core.Cell;
import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.DistrictKey;
import com.scarasol.citylines.road.core.V3Footprint;
import com.scarasol.citylines.road.core.V3Params;
import com.scarasol.citylines.road.core.V3Planner;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.config.StreetGenerationMode;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityWorldGenData;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.Orientation;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.BuildingPart;
import mcjty.lostcities.worldgen.lost.cityassets.MultiBuilding;
import mcjty.lostcities.setup.CustomRegistries;
import mcjty.lostcities.worldgen.lost.regassets.data.DataTools;
import mcjty.lostcities.worldgen.lost.cityassets.WorldStyle;
import mcjty.lostcities.worldgen.lost.regassets.WorldStyleRE;
import mcjty.lostcities.worldgen.street.HierarchicalStreetPlanner;
import mcjty.lostcities.worldgen.street.PlannedRoadType;
import mcjty.lostcities.worldgen.street.PlannedStreetInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Constructor-time road inputs, with active generation governed by the shared dimension binding. */
public final class CitylinesRoadState {

    private static final String LC2H_LOG_PREFIX = "[Citylines/LC2H]";

    /** One refusal/acceptance line per dimension and cause; dimension init must not spam. */
    private static final Set<String> LOGGED_LC2H_NOTICES = ConcurrentHashMap.newKeySet();

    /**
     * Debug-only diagnostic state; bounded so a long run cannot grow it without limit.
     * None of it is ever consulted by generation.
     */
    private static final Set<String> LOGGED_PLAN_SUMMARIES = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_ASSET_ANCHORS = ConcurrentHashMap.newKeySet();
    private static final int MAX_PLAN_SUMMARIES = 64;
    private static final int MAX_ASSET_ANCHOR_LOGS = 8;

    private final IDimensionInfo provider;
    private final String dimensionId;
    private final long seed;
    private final boolean active;
    private final String decisionReason;
    private final V3Params v3Params;
    private final List<V3Footprint> v3Footprints;
    /** The only land-road planner; construction is lazy but its inputs are frozen. */
    private volatile V3Planner v3Planner;
    private volatile BuildingPart bridgeDeck;
    private CitylinesRoadState(IDimensionInfo provider, String dimensionId, long seed, boolean active,
                               String decisionReason, List<V3Footprint> footprints) {
        this.provider = provider;
        this.dimensionId = dimensionId;
        this.seed = seed;
        this.active = active;
        this.decisionReason = decisionReason;
        this.v3Params = V3Params.selectedForDefaultArea();
        this.v3Footprints = List.copyOf(footprints);
    }

    /**
     * The shapes the design enumerates and the shipped asset set covers, used only when the
     * loaded asset list cannot be read: protecting nothing is worse than protecting these.
     */
    private static final List<V3Footprint> ENUMERATED_FOOTPRINTS =
            List.of(new V3Footprint(3, 3), new V3Footprint(2, 2), new V3Footprint(2, 1));

    /**
     * The multi-building footprints this dimension may have to protect, read from the assets that
     * are actually loaded (decision D12).
     *
     * <p>The contract behind {@code preserveRareParcel()} works on the <b>real</b> asset list: a
     * datapack may ship a 7x7 or 9x9 multi-building, and the rare-footprint fallback only
     * guarantees a candidate rectangle for the shapes it is told about. Reading the loaded assets
     * therefore switches that fallback on by itself the moment such an asset exists, instead of
     * leaving it permanently dead behind a hardcoded list.
     *
     * <p>Anything wider than the dimension's multi-building tile is dropped: Lost Cities cannot
     * place a footprint its own {@code MultiChunk} grid cannot hold. The list also enters the plan
     * fingerprint, as the contract requires.
     */
    static List<V3Footprint> loadedFootprints(CommonLevelAccessor level, int areaSize) {
        try {
            // The registries are filled per level, and `AssetRegistries.load` only fills parts,
            // buildings and stuff - the multi-building registry is not loaded until something asks
            // for one. A dimension info built before that sees an empty list and would silently
            // protect the enumerated shapes only, which is exactly the gap D12 exists to close.
            // loadAll is idempotent (it skips names already present).
            AssetRegistries.MULTI_BUILDINGS.loadAll(level);
            List<V3Footprint> assets = new ArrayList<>();
            for (MultiBuilding building : AssetRegistries.MULTI_BUILDINGS.getIterable()) {
                assets.add(new V3Footprint(building.getDimX(), building.getDimZ()));
            }
            List<V3Footprint> shapes = footprintsWithin(assets, areaSize);
            if (!shapes.isEmpty()) {
                return shapes;
            }
            CitylinesMod.LOGGER.warn("[citylines] no multi-building asset is loaded; protecting the "
                    + "enumerated shapes so a datapack cannot silently disable parcel protection");
        } catch (RuntimeException e) {
            CitylinesMod.LOGGER.warn("[citylines] cannot read the loaded multi-building assets ({}); "
                    + "protecting the enumerated shapes instead", e.toString());
        }
        return ENUMERATED_FOOTPRINTS;
    }

    /**
     * Keeps the usable shapes of a candidate list: deduplicated, without empty ones and without
     * anything wider than the multi-building tile (Lost Cities cannot place those anyway).
     */
    static List<V3Footprint> footprintsWithin(Iterable<V3Footprint> candidates, int areaSize) {
        Set<V3Footprint> shapes = new LinkedHashSet<>();
        for (V3Footprint shape : candidates) {
            if (shape.x() >= 1 && shape.z() >= 1 && shape.x() <= areaSize && shape.z() <= areaSize) {
                shapes.add(shape);
            }
        }
        return List.copyOf(shapes);
    }

    /**
     * The dimension's multi-building tile, read from the world style it actually resolved.
     *
     * <p>{@code MultiChunk} tiles the world into {@code areasize x areasize} chunk squares and
     * sizes its own grid with it, so V3's parcel protection must stride exactly the same number.
     * The value is read from the resolved {@code WorldStyle} rather than the profile, because
     * LC2H overwrites the profile getters with unprimed ThreadLocal implementations.
     */
    public int multiAreaSize() {
        // getWorldStyle() is the *resolved* style, which already applied the style's own
        // multisettings block (including any inherited default), so this is the tile MultiChunk
        // will actually use for this dimension.
        WorldStyle style = provider.getWorldStyle();
        return style == null ? -1 : style.getMultiSettings().areasize();
    }

    /**
     * The one decision taken per {@code DefaultDimensionInfo} construction.
     *
     * @param worldStyle  style the constructor must resolve (Citylines style only when Citylines is
     *                    actually going to run for this dimension)
     * @param eligible  whether this dimension runs Citylines
     * @param reason      human-readable cause, surfaced in the log and by the command
     */
    public record ConstructorDecision(String worldStyle, boolean eligible, String reason) {
    }

    /**
     * Decides everything about a dimension before it exists, from the constructor's own
     * arguments. Called by the {@code DefaultDimensionInfo} mixin <b>inside</b> the
     * constructor, at the point where the world style is resolved, and its result is
     * reused verbatim at the constructor tail — so the style and the activation verdict
     * come from the same evaluation.
     */
    public static ConstructorDecision decideForConstructor(WorldGenLevel world, LostCityProfile profile,
                                                           String originalStyle) {
        Decision decision = decideActive(world, profile);
        String style = originalStyle;
        if (decision.active()
                && originalStyle != null
                && !RoadAssetPrecheck.WORLDSTYLE.equals(originalStyle)
                && RoadAssetPrecheck.assetChainResolves(world)) {
            style = RoadAssetPrecheck.WORLDSTYLE;
        }
        return new ConstructorDecision(style, decision.active(), decision.reason());
    }

    /**
     * Called from the {@code DefaultDimensionInfo} constructor mixin. Verifies that
     * every asset the Citylines renderer and this dimension's chunk palettes need actually
     * resolves, then binds the state to the planner instance the dimension just
     * created.
     *
     * <p>The pre-check runs exactly once per dimension and before any Citylines chunk is
     * generated. It must pass as a whole: Lost Cities resolves parts/palettes/styles
     * lazily and a missing id makes {@code RegistryAssetRegistry.get} rethrow, which
     * would crash chunk generation in the middle of an already started Citylines dimension —
     * the one thing the design forbids. On failure the dimension simply stays on TLC.
     */
    public static void attach(IDimensionInfo info, WorldGenLevel world, LostCityProfile profile,
                              ConstructorDecision decision) {
        HierarchicalStreetPlanner v1 = info.getStreetPlanner();
        if (v1 == null) {
            return;
        }
        // Dimension id and seed come from the world argument, never from the
        // IDimensionInfo being constructed: LC2H also overwrites getType()/getSeed()
        // with ThreadLocal-backed getters, and the world argument is the same value
        // the constructor used.
        String dimensionId = dimensionIdOf(world);
        long seed = world.getSeed();
        boolean active = decision.eligible();
        String reason = decision.reason();
        if (active) {
            RoadAssetPrecheck.Result assets = RoadAssetPrecheck.validate(world, decision.worldStyle());
            if (!assets.ok()) {
                active = false;
                reason = "Citylines road assets do not resolve for this dimension: " + assets.problems().size()
                        + " problem(s)";
                CitylinesMod.LOGGER.error("[citylines] Citylines stays disabled for {}: {} road asset(s) do not resolve: {}",
                        dimensionId, assets.problems().size(), String.join(", ", assets.problems()));
                } else if (!areaSizeMatches(world, decision.worldStyle())) {
                active = false;
                reason = "the world style's multi-building tile does not match V3's frozen scale";
                CitylinesMod.LOGGER.error("[citylines] V3 stays disabled for {}: the resolved world style '{}' "
                                + "does not use a {}-chunk multi-building tile (V3Params.areaSize()={}). "
                                + "V3's parcel protection strides that tile, so reusing the default parameters "
                                + "would protect rectangles MultiChunk can never fill. Set multisettings.areasize "
                                + "to {} in that world style, or use a supported world style.",
                        dimensionId, decision.worldStyle(), V3Params.selectedForDefaultArea().areaSize(),
                        V3Params.selectedForDefaultArea().areaSize(),
                        V3Params.selectedForDefaultArea().areaSize());
            } else if (!assets.controlledDamage()) {
                CitylinesMod.LOGGER.warn("[citylines] Citylines road assets resolve for {}, but its world style '{}' does not "
                                + "reach {}: road damage keeps the default mapping (no controlled damaged rules)",
                        dimensionId, decision.worldStyle(), RoadAssetPrecheck.STYLE);
            }
        }
        List<V3Footprint> footprints = active
                ? loadedFootprints(world, V3Params.selectedForDefaultArea().areaSize()) : List.of();
        CitylinesRoadState state = new CitylinesRoadState(info, dimensionId, seed, active, reason,
                footprints);
        // The target class is final, so the compiler refuses a direct cast to our
        // duck interface; going through Object keeps it a plain checked cast (Mixin
        // adds the interface at load time). This is not reflection.
        ((CitylinesStreetPlannerAccess) (Object) v1).citylines$setRoadState(state);
        if (state.active) {
            // The footprint list is part of the plan fingerprint and decides whether the rare
            // footprint fallback can fire at all, so it is logged with the rest of the enable line.
            CitylinesMod.LOGGER.info("[citylines] road inputs ready for {} (S={}, areaSize={}, footprints={}, fingerprint={})",
                    state.dimensionId, state.v3Params().supercellSize(), state.v3Params().areaSize(),
                    describeFootprints(footprints), Long.toHexString(state.v3Planner().fingerprint()));
        }
    }

    /**
     * Convenience for callers that did not pre-compute a decision; it runs the same
     * {@link #decideForConstructor} predicate, so it cannot disagree with the mixin
     * path.
     */
    public static void attach(IDimensionInfo info, WorldGenLevel world, LostCityProfile profile) {
        attach(info, world, profile, decideForConstructor(world, profile,
                profile == null ? null : profile.getWorldStyle()));
    }

    /**
     * The world style a dimension should resolve. Kept for callers outside the
     * constructor mixin; it delegates to the same single predicate.
     */
    public static String worldStyleFor(WorldGenLevel world, LostCityProfile profile, String originalStyle) {
        return decideForConstructor(world, profile, originalStyle).worldStyle();
    }

    /**
     * Whether the dimension's resolved multi-building tile matches the V3 parameter block.
     *
     * <p>Step 2 of the V3 checklist: {@code V3Params.selectedForDefaultArea()} is only valid for
     * {@code MultiSettings.areasize() == V3Params.areaSize()}. {@code MultiChunk} sizes its own
     * {@code buildingGrid} with that tile, so a mismatch would make V3 protect rectangles TLC can
     * never fill (or miss ones it would), which is why this fails closed instead of reusing the
     * default parameters. A datapack can ship a world style with a different tile, so the value has
     * to be read live for the style this dimension actually resolved.
     *
     * @param worldStyleId the style the dimension resolved, for the log line only
     */
    static boolean areaSizeMatches(WorldGenLevel world, String worldStyleId) {
        if (world == null || worldStyleId == null) {
            return false;
        }
        var registry = world.registryAccess()
                .registry(CustomRegistries.WORLDSTYLES_REGISTRY_KEY).orElse(null);
        if (registry == null) {
            return false;
        }
        WorldStyleRE style = registry.get(DataTools.fromName(worldStyleId));
        return style != null && style.getMultiSettings().areasize() == V3Params.selectedForDefaultArea().areaSize();
    }

    /** Whether this dimension would run Citylines; same single predicate as the mixin path. */
    public static boolean wouldManage(WorldGenLevel world, LostCityProfile profile) {
        return decideActive(world, profile).active();
    }

    /**
     * The shared predicate behind {@link #decideForConstructor}: every guard used by
     * both the world-style decision and the activation decision lives here and only
     * here.
     *
     * <p>Nothing in this method touches the {@code IDimensionInfo} under construction.
     * Under LC2H the effective mode question is answered from LC2H's own config file
     * ({@code lostCitiesStreetGenerationMode}, whose absent/garbled value LC2H itself
     * treats as {@code HIERARCHICAL_GRID_V1}) plus the ChaosZPack profile rule — never
     * from {@code info.getStreetGenerationMode()}, which LC2H {@code @Overwrite}s with
     * a ThreadLocal-backed getter that is not primed during construction.
     */
    private static Decision decideActive(WorldGenLevel world, LostCityProfile profile) {
        if (!CitylinesConfig.INSTANCE.enabled()) {
            return new Decision(false, "enabled=false");
        }
        if (profile == null || world == null) {
            return new Decision(false, "no Lost Cities profile for this dimension");
        }
        // Profiles whose terrain model is not a flat city surface have no equivalent
        // v1 feasibility rule for roads; Citylines stays off instead of guessing.
        if (!profile.isDefault() || !(world.getLevel().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)
                || profile.GROUNDLEVEL <= world.getMinBuildHeight() + 1
                || profile.GROUNDLEVEL >= world.getMaxBuildHeight() - 1) {
            return new Decision(false, "profile '" + profile.getName() + "' has no flat city surface (space/spheres)");
        }
        if (ModCompat.isLc2hLoaded()) {
            return decideActiveWithLc2h(world, profile);
        }
        // TLC only consults the street planner in this mode; Citylines is delivered through
        // it, so any other persisted mode means "not our dimension". The value is read
        // through LostCityWorldGenData — the same public lookup DefaultDimensionInfo
        // assigns to its field one line after the world style is resolved.
        StreetGenerationMode mode = effectiveStreetMode(world, profile);
        if (mode != StreetGenerationMode.HIERARCHICAL_GRID_V1) {
            return new Decision(false, "street generation mode is " + mode + ", not HIERARCHICAL_GRID_V1");
        }
        return new Decision(true, "Citylines eligible: LC2H absent, mode=HIERARCHICAL_GRID_V1, profile='"
                + profile.getName() + "'");
    }

    /** LC2H is loaded: the policy plus the detected LC2H switches decide. */
    private static Decision decideActiveWithLc2h(WorldGenLevel world, LostCityProfile profile) {
        Lc2hStatus status = ModCompat.lc2hStatus();
        Lc2hStatus.Gate gate = lc2hGate(status, profile);
        boolean allowed = applyLc2hGate(dimensionIdOf(world), status, gate);
        return new Decision(allowed, allowed ? "Citylines eligible: " + gate.reason() : gate.reason());
    }

    /**
     * The single LC2H predicate. Both the constructor-time style decision and the
     * activation decision go through this method with the same {@code profile}
     * instance, so a dimension can never get the Citylines world style while Citylines is refused
     * (or the reverse).
     */
    private static Lc2hStatus.Gate lc2hGate(Lc2hStatus status, LostCityProfile profile) {
        // LC2H's LostCitiesStreetModePolicy: only an explicit non-HGV1 value (LEGACY)
        // is unsafe; absent/blank/unknown falls back to LC2H's own HIERARCHICAL_GRID_V1
        // default, and an unreadable file must not disable Citylines on its own.
        boolean modeIsHv1 = Lc2hStatus.MODE_HIERARCHICAL_GRID_V1.equals(status.streetGenerationMode());
        return Lc2hStatus.gate(status, profile.getName(), modeIsHv1);
    }

    /**
     * Applies one gate result: refuses (with the single structured ERROR) or lets Citylines
     * through, logging the detection when {@code lc2hPolicy=ALLOW} accepted a risk.
     *
     * @return whether Citylines may run
     */
    private static boolean applyLc2hGate(String dimensionId, Lc2hStatus status, Lc2hStatus.Gate gate) {
        if (!gate.allowed() && LOGGED_LC2H_NOTICES.add("refuse|" + dimensionId + '|' + gate.reason())) {
            CitylinesMod.LOGGER.error("{} dim={} detected={} reason={} fix={}",
                    LC2H_LOG_PREFIX, dimensionId, status.describe(), gate.reason(), gate.fix());
        }
        return gate.allowed();
    }

    private static String dimensionIdOf(WorldGenLevel world) {
        return world.getLevel().dimension().location().toString();
    }

    /**
     * Persisted-mode lookup exactly as {@code DefaultDimensionInfo} performs it; the
     * profile argument is only the fallback for a dimension without a persisted mode.
     */
    private static StreetGenerationMode effectiveStreetMode(WorldGenLevel world, LostCityProfile profile) {
        try {
            return LostCityWorldGenData.get(world.getLevel())
                    .getStreetMode(world.getLevel().dimension(), profile.STREET_GENERATION_MODE);
        } catch (RuntimeException e) {
            // Untyped/unloaded level: refuse to switch rather than guess.
            CitylinesMod.LOGGER.warn("[citylines] cannot read the persisted street mode: {}", e.toString());
            return null;
        }
    }

    /** Whether this dimension runs Citylines, and why (also surfaced by {@code /citylines roads}). */
    private record Decision(boolean active, String reason) {
    }

    public boolean isEligible() {
        return active;
    }

    public boolean isActive() {
        return active && TerrainOwnership.isManaged(provider);
    }

    /** Why Citylines is on or off for this dimension; diagnostics only. */
    public String decisionReason() {
        return decisionReason;
    }

    /**
     * True when this dimension runs Citylines. Uses only a compile-time interface cast
     * (never reflection) and is safe to call for dimensions that never created a Citylines
     * state.
     */
    public static boolean isManaged(IDimensionInfo provider) {
        CitylinesRoadState state = of(provider);
        return state != null && state.isActive();
    }

    /** The attached state of a dimension, or {@code null} when there is none. */
    public static CitylinesRoadState of(IDimensionInfo provider) {
        if (provider == null) {
            return null;
        }
        // Declared as Object because the TLC planner is final: the instanceof below
        // must be resolved against the runtime type added by Mixin, not statically.
        Object planner = provider.getStreetPlanner();
        if (planner instanceof CitylinesStreetPlannerAccess access) {
            return access.citylines$roadState();
        }
        return null;
    }

    public long fingerprint() {
        return v3Planner().fingerprint();
    }

    public String dimensionId() {
        return dimensionId;
    }

    /**
     * Lazily built V3 planner; one per dimension.
     *
     * <p>The fact source is the same frozen layer Citylines uses ({@link TlcV3FactSource} over
     * {@link TlcRoadFacts}), so V3 never reads final {@code BuildingInfo}/{@code MultiChunk} state
     * and cannot depend on which chunk generated first. Cold planning reads the fact source while
     * holding this planner's own monitor; the fact source itself takes no lock, so there is no lock
     * order to invert with TLC's dimension lock.
     */
    public V3Planner v3Planner() {
        V3Planner local = v3Planner;
        if (local == null) {
            synchronized (this) {
                local = v3Planner;
                if (local == null) {
                    local = new V3Planner(seed, dimensionId, v3Params(),
                            new TlcV3FactSource(new TlcRoadFacts(provider)), v3Footprints());
                    v3Planner = local;
                }
            }
        }
        return local;
    }

    /** The V3 parameter block this dimension is planned with. */
    public V3Params v3Params() {
        return v3Params;
    }

    /**
     * The multi-building footprints V3 may protect.
     *
     * <p>Currently the shapes the Citylines asset set covers, because V3's parcel protection works on
     * {@code MultiChunk}'s {@code areasize} tiling and these are the sizes the design enumerates.
     * A footprint larger than {@code areaSize} is rejected by the planner constructor, so an
     * over-large entry would fail loudly rather than silently protect nothing.
     */
    public List<V3Footprint> v3Footprints() {
        return v3Footprints;
    }

    /**
     * Road answer in Lost Cities terms, for {@code HierarchicalStreetPlanner.getStreetInfo}.
     *
     * <p>Answered from the V3 effective graph ({@link V3RoadGraph}), which reads the plan that owns
     * the coordinate and the cell's <b>explicit</b> logical edges — never "both chunks are roads,
     * so they connect". This is the single entry point every TLC consumer already goes through, so
     * one implementation serves them all.
     */
    public PlannedStreetInfo streetInfo(int chunkX, int chunkZ) {
        return V3RoadGraph.streetInfo(v3Planner().infoAt(chunkX, chunkZ), chunkX, chunkZ);
    }

    /** Road answer in Lost Cities terms, for {@code HierarchicalStreetPlanner.getRoadType}. */
    public PlannedRoadType roadType(int chunkX, int chunkZ) {
        return V3RoadGraph.toPlanned(v3Planner().infoAt(chunkX, chunkZ).roadType());
    }

    /** The V3 cell for a chunk, for the rendering layer and the diagnostics. */
    public com.scarasol.citylines.road.core.V3CellInfo v3Cell(int chunkX, int chunkZ) {
        return v3Planner().infoAt(chunkX, chunkZ);
    }

    /** Footprint shapes for the enable log line, largest first. */
    private static String describeFootprints(List<V3Footprint> footprints) {
        return footprints.stream()
                .sorted(java.util.Comparator.comparingInt(V3Footprint::area).reversed()
                        .thenComparingInt(V3Footprint::x).thenComparingInt(V3Footprint::z))
                .map(shape -> shape.x() + "x" + shape.z())
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /**
     * Whether this dimension's plan reserves the chunk as a road.
     *
     * <p>Reserved means "carries a road class <b>and</b> at least one explicit planned edge": a
     * class without an edge is a chunk whose road will not be rendered, so it must not reserve
     * anything. This is the single predicate behind both the effective road class and the
     * whole-footprint building veto, so the two cannot drift apart.
     */
    public boolean roadReservedAt(int chunkX, int chunkZ) {
        return V3RoadGraph.isReservedRoad(v3Cell(chunkX, chunkZ));
    }

    /** The V3 plan owning a chunk, for diagnostics. */
    public com.scarasol.citylines.road.core.V3Plan v3PlanFor(int chunkX, int chunkZ) {
        return v3Planner().planAt(chunkX, chunkZ);
    }

    /** The Citylines deck part, resolved once per dimension. */
    public BuildingPart bridgeDeckPart() {
        BuildingPart local = bridgeDeck;
        if (local == null) {
            local = AssetRegistries.PARTS.get(provider.getWorld(),
                    new ResourceLocation(RoadAssetPrecheck.BRIDGE_DECK));
            bridgeDeck = local;
        }
        return local;
    }


}
