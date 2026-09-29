package com.scarasol.citylines.road.tlc;

import mcjty.lostcities.setup.CustomRegistries;
import mcjty.lostcities.worldgen.lost.regassets.BuildingPartRE;
import mcjty.lostcities.worldgen.lost.regassets.CityStyleRE;
import mcjty.lostcities.worldgen.lost.regassets.PaletteRE;
import mcjty.lostcities.worldgen.lost.regassets.StyleRE;
import mcjty.lostcities.worldgen.lost.regassets.WorldStyleRE;
import mcjty.lostcities.worldgen.lost.regassets.data.CityStyleSelector;
import mcjty.lostcities.worldgen.lost.regassets.data.DataTools;
import mcjty.lostcities.worldgen.lost.regassets.data.ObjectSelector;
import mcjty.lostcities.worldgen.lost.regassets.data.PaletteSelector;
import mcjty.lostcities.worldgen.lost.regassets.data.StreetParts;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dimension pre-check for the Citylines road assets.
 *
 * <p>Lost Cities resolves parts, palettes, styles and city styles lazily, and a
 * missing asset is <b>not</b> a warning: {@code RegistryAssetRegistry.get} wraps the
 * registry lookup in a try/catch and rethrows
 * {@code RuntimeException("Error getting resource ...")} because the {@code *RE}
 * constructors dereference a {@code null} entry. A single missing
 * {@code road_*} part, palette, style, city style or world style therefore crashes
 * chunk generation in the middle of a dimension. The design forbids switching a
 * dimension back to TLC halfway, so the whole chain is verified once, before Citylines is
 * activated for the dimension, and a failure simply leaves the dimension on TLC.
 *
 * <p>The check is deliberately cheap and read-only:
 * <ul>
 *   <li>only dynamic-registry lookups ({@code registryAccess().registryOrThrow(...)})
 *       and record accessors of the already-parsed {@code *RE} objects are used --
 *       no {@code AssetRegistries} instantiation, no world queries, no I/O;</li>
 *   <li>the visited sets bound the walk, so a cyclic or huge data pack cannot make
 *       the constructor slow.</li>
 * </ul>
 *
 * <p>It runs where {@link CitylinesRoadState#attach} runs, i.e. at the tail of the
 * {@code DefaultDimensionInfo} constructor. The dimension's own world style is read
 * from the constructor's {@link LostCityProfile} argument and never from
 * {@code info.getWorldStyle()} / {@code info.getProfile()}, which LC2H overwrites
 * with ThreadLocal-backed getters that are not yet primed at that point.
 */
public final class RoadAssetPrecheck {

    /** The Citylines asset chain the renderer references. */
    public static final String WORLDSTYLE = "citylines:standard";
    public static final String CITYSTYLE = "citylines:standard";
    public static final String STYLE = "citylines:standard";
    /** Palette referenced by every part's {@code refpalette}. */
    public static final String Citylines_PART_PALETTE = RoadPartTable.PALETTE;
    /** Palette that carries the controlled {@code damaged} rules into the chunk palette. */
    public static final String Citylines_DAMAGE_PALETTE = "citylines:road_damage";
    /**
     * The single Citylines bridge deck part. It is not a street piece (two slices: road
     * surface + railing), so it is not in {@link RoadPartTable}; it must still resolve
     * because a Citylines dimension substitutes it for whatever bridge part the CityStyle
     * selector produced.
     */
    public static final String BRIDGE_DECK = "citylines:bridge_deck";

    private static final int MAX_VISITS = 256;
    private static final int MAX_INHERIT_DEPTH = 16;

    /**
     * @param ok              every referenced asset resolves and is well formed
     * @param problems        human-readable list of missing/malformed ids (empty when ok)
     * @param controlledDamage the dimension's own style chain reaches the Citylines style, so
     *                        the chunk palette carries the controlled damage rules
     */
    public record Result(boolean ok, List<String> problems, boolean controlledDamage) {
    }

    private RoadAssetPrecheck() {
    }

    /**
     * Validate the whole resolved chain of a dimension: the 29 Citylines parts, the Citylines
     * worldstyle -> citystyle -> style -> palettes chain, every CityStyle the given
     * worldstyle can select (including {@code inherit}) with its street part slots and
     * palettes, and finally the outside style.
     *
     * @param effectiveWorldStyle the world style the dimension will really resolve
     *                            (already substituted by the constructor hook when the
     *                            dimension runs Citylines), or {@code null} when unset
     */
    public static Result validate(WorldGenLevel world, String effectiveWorldStyle) {
        List<String> problems = new ArrayList<>();
        Set<ResourceLocation> wantedPalettes = new LinkedHashSet<>();
        Set<ResourceLocation> wantedParts = new LinkedHashSet<>();
        boolean controlledDamage = false;

        try {
            RegistryAccess access = world.registryAccess();
            Registry<BuildingPartRE> partRegistry = access.registryOrThrow(CustomRegistries.PART_REGISTRY_KEY);
            Registry<PaletteRE> paletteRegistry = access.registryOrThrow(CustomRegistries.PALETTE_REGISTRY_KEY);
            Registry<StyleRE> styleRegistry = access.registryOrThrow(CustomRegistries.STYLE_REGISTRY_KEY);
            Registry<CityStyleRE> citystyleRegistry = access.registryOrThrow(CustomRegistries.CITYSTYLES_REGISTRY_KEY);
            Registry<WorldStyleRE> worldstyleRegistry = access.registryOrThrow(CustomRegistries.WORLDSTYLES_REGISTRY_KEY);

            // Fail closed on coverage as well as on files: a key with no table entry is a chunk the
            // renderer cannot pave, and checking only the part list would miss it. Every reachable
            // key (Citylines collector/arterial, plus the ten V3 local port keys) must resolve.
            List<String> uncovered = RoadPartTable.unresolvedReachableKeys();
            if (!uncovered.isEmpty()) {
                problems.add("unreachable table coverage: " + String.join(", ", uncovered));
            }

            for (String partName : RoadPartTable.partIds()) {
                ResourceLocation id = DataTools.fromName(partName);
                BuildingPartRE part = partRegistry.get(id);
                if (part == null) {
                    problems.add("part " + id);
                    continue;
                }
                if (part.getxSize() != 16 || part.getzSize() != 16 || part.getSlices().length != 1) {
                    problems.add("part " + id + " is not a single-slice 16x16 piece");
                    continue;
                }
                for (String slice : part.getSlices()) {
                    if (slice.length() != part.getxSize() * part.getzSize()) {
                        problems.add("part " + id + " has a malformed slice");
                        break;
                    }
                }
                String refPalette = part.getRefPaletteName();
                if (refPalette == null || refPalette.trim().isEmpty()) {
                    problems.add("part " + id + " has no refpalette");
                } else {
                    wantedPalettes.add(DataTools.fromName(refPalette));
                }
            }
            wantedPalettes.add(DataTools.fromName(Citylines_PART_PALETTE));
            wantedPalettes.add(DataTools.fromName(Citylines_DAMAGE_PALETTE));

            // The Citylines chain the renderer/asset set promises.
            Walker current = new Walker(styleRegistry, citystyleRegistry, worldstyleRegistry);
            current.worldstyle(WORLDSTYLE);
            current.citystyle(CITYSTYLE, 0);
            current.style(STYLE);
            problems.addAll(current.problems);
            wantedPalettes.addAll(current.palettes);
            wantedParts.addAll(current.parts);

            // The chain this dimension actually resolves (the Citylines one when the constructor
            // hook substituted it): every chunk palette of the dimension comes from it,
            // and a missing palette there throws in Style.getRandomPalette.
            Walker active = new Walker(styleRegistry, citystyleRegistry, worldstyleRegistry);
            if (effectiveWorldStyle == null || effectiveWorldStyle.trim().isEmpty()) {
                problems.add("effective worldstyle is unset");
            } else {
                active.worldstyle(effectiveWorldStyle);
            }
            problems.addAll(active.problems);
            wantedPalettes.addAll(active.palettes);
            wantedParts.addAll(active.parts);
            controlledDamage = active.reachedStyles.contains(DataTools.fromName(STYLE));

            for (ResourceLocation part : wantedParts) {
                if (partRegistry.get(part) == null) {
                    problems.add("part " + part);
                }
            }
            for (ResourceLocation palette : wantedPalettes) {
                if (paletteRegistry.get(palette) == null) {
                    problems.add("palette " + palette);
                }
            }
        } catch (RuntimeException e) {
            // A registry that cannot be read at all is a hard "cannot verify" and must
            // keep the dimension on TLC rather than risk a mid-dimension crash.
            problems.add("asset registry unavailable: " + e);
        }

        return new Result(problems.isEmpty(), List.copyOf(problems), controlledDamage);
    }

    /**
     * Cheap yes/no used by the constructor-time world style decision: the Citylines chain
     * (worldstyle, citystyle, style, palettes, all 29 parts and the citystyle's street
     * part slots) resolves. Only in-memory registry lookups.
     */
    public static boolean assetChainResolves(WorldGenLevel world) {
        return validate(world, WORLDSTYLE).ok();
    }

    /**
     * Read-only walk over worldstyle -> citystyle (+ inherit) -> style -> palettes.
     * Every id that does not resolve is recorded once; cycles and size are bounded.
     */
    private static final class Walker {

        private final Registry<StyleRE> styles;
        private final Registry<CityStyleRE> citystyles;
        private final Registry<WorldStyleRE> worldstyles;

        private final List<String> problems = new ArrayList<>();
        private final Set<ResourceLocation> palettes = new LinkedHashSet<>();
        /** Street part ids referenced by the visited CityStyles; verified in the part registry. */
        private final Set<ResourceLocation> parts = new LinkedHashSet<>();
        /** Style ids reached from this walk's roots; used for the controlled-damage answer. */
        private final Set<ResourceLocation> reachedStyles = new LinkedHashSet<>();
        private final Set<ResourceLocation> visitedStyles = new HashSet<>();
        private final Set<ResourceLocation> visitedCityStyles = new HashSet<>();
        private int visits;

        Walker(Registry<StyleRE> styles, Registry<CityStyleRE> citystyles, Registry<WorldStyleRE> worldstyles) {
            this.styles = styles;
            this.citystyles = citystyles;
            this.worldstyles = worldstyles;
        }

        void worldstyle(String name) {
            ResourceLocation id = DataTools.fromName(name);
            WorldStyleRE worldstyle = worldstyles.get(id);
            if (worldstyle == null) {
                problems.add("worldstyle " + id);
                return;
            }
            if (worldstyle.getOutsideStyle() != null) {
                style(worldstyle.getOutsideStyle());
            }
            for (CityStyleSelector selector : worldstyle.getCityStyleSelectors()) {
                citystyle(selector.citystyle(), 0);
            }
        }

        void citystyle(String name, int depth) {
            if (depth > MAX_INHERIT_DEPTH) {
                problems.add("citystyle inherit chain too deep at " + name);
                return;
            }
            if (!budget()) {
                return;
            }
            ResourceLocation id = DataTools.fromName(name);
            if (!visitedCityStyles.add(id)) {
                return;
            }
            CityStyleRE citystyle = citystyles.get(id);
            if (citystyle == null) {
                problems.add("citystyle " + id);
                return;
            }
            if (citystyle.getStyle() != null) {
                style(citystyle.getStyle());
            }
            // The TLC fallback path (declined chunks, open lots, parks) still uses the
            // CityStyle's street slots, so their parts must resolve too.
            citystyle.getStreetSettings().ifPresent(settings -> {
                streetParts(settings.getParts());
                streetParts(settings.getLargeParts());
                streetParts(settings.getTertiaryParts());
            });
            // Bridge parts are resolved with getOrWarn (a missing one degrades to the
            // TLC deck rather than crashing), but a Citylines dimension substitutes the Citylines deck
            // for every bridge, so a missing deck would silently leave Citylines spans
            // unrendered. Verify every bridge selector id the same way as a street slot.
            citystyle.getSelectors().ifPresent(selectors -> {
                selectors.getBridgeSelector().ifPresent(this::selectorParts);
                selectors.getLargeBridgeSelector().ifPresent(this::selectorParts);
            });
            if (citystyle.getInherit() != null) {
                citystyle(citystyle.getInherit(), depth + 1);
            }
        }

        private void streetParts(StreetParts slots) {
            if (slots == null) {
                return;
            }
            for (List<String> slot : List.of(slots.full(), slots.straight(), slots.end(), slots.bend(), slots.t(),
                    slots.none(), slots.all(), slots.connector(), slots.stair())) {
                for (String name : slot) {
                    parts.add(DataTools.fromName(name));
                }
            }
        }

        /** Part ids a CityStyle's bridge selectors can pick. */
        private void selectorParts(List<ObjectSelector> selectors) {
            for (ObjectSelector selector : selectors) {
                parts.add(DataTools.fromName(selector.value()));
            }
        }

        void style(String name) {
            if (!budget()) {
                return;
            }
            ResourceLocation id = DataTools.fromName(name);
            if (!visitedStyles.add(id)) {
                return;
            }
            StyleRE style = styles.get(id);
            if (style == null) {
                problems.add("style " + id);
                return;
            }
            reachedStyles.add(id);
            for (List<PaletteSelector> group : style.getRandomPaletteChoices()) {
                for (PaletteSelector selector : group) {
                    palettes.add(DataTools.fromName(selector.palette()));
                }
            }
        }

        private boolean budget() {
            if (visits >= MAX_VISITS) {
                problems.add("asset graph exceeds " + MAX_VISITS + " lookups");
                return false;
            }
            visits++;
            return true;
        }
    }
}
