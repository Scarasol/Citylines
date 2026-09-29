package com.scarasol.citylines.terrain;

import com.scarasol.citylines.CitylinesMod;
import com.scarasol.citylines.compat.ExtractionCitiesDomainCheck;
import com.scarasol.citylines.config.CitylinesConfig;
import com.scarasol.citylines.road.tlc.CitylinesRoadState;
import mcjty.lostcities.config.LandscapeType;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

/** One current generation rule per dimension. Initialization and persistence are server-thread only. */
public final class CityGroundBinding {
    public enum State {
        NOT_READY,
        READY,
        NATIVE,
        BROKEN
    }

    private final ServerLevel level;
    private final String dimensionId;
    private final int minY;
    private final int maxY;
    private final boolean noiseGeneratorAvailable;
    private volatile State state = State.NOT_READY;
    private volatile String reason = "not resolved yet";
    private volatile int ground;
    private volatile String identity = "";
    private boolean recordRead;
    private String persistedIdentity;
    private int persistedGround;
    private RegionState regionState = RegionState.UNKNOWN;
    private volatile boolean rawGatesEnabled;
    private volatile boolean providerGaveUp;
    private String baseIdentity;

    CityGroundBinding(ServerLevel level, int minY, int maxY, boolean noiseGeneratorAvailable) {
        this.level = level;
        this.dimensionId = level.dimension().location().toString();
        this.minY = minY;
        this.maxY = maxY;
        this.noiseGeneratorAvailable = noiseGeneratorAvailable;
    }

    public State state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public int ground() {
        return ground;
    }

    public String identity() {
        return identity;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    void markProviderAbsent() {
        providerGaveUp = true;
    }

    boolean expectsPlane() {
        return rawGateScope(state, rawGatesEnabled);
    }

    static boolean rawGateScope(State state, boolean eligible) {
        return eligible && (state == State.NOT_READY || state == State.READY || state == State.BROKEN);
    }

    /** Read-only ownership is available to raw prechecks before the first generation task is allowed. */
    public boolean ownsTerrain() {
        if (state == State.BROKEN) {
            throw new IllegalStateException("Citylines generation refused for " + dimensionId + ": " + reason);
        }
        if (state == State.NOT_READY && !rawGatesEnabled) {
            throw new IllegalStateException("Citylines generation inputs are not ready for " + dimensionId);
        }
        return state != State.NATIVE && rawGatesEnabled;
    }

    void breakContract(String failure) {
        reason = failure;
        state = State.BROKEN;
    }

    /** Level.Load and CreateSpawnPosition share this idempotent entry; neither proves emptiness. */
    void resolveAtLoad(IDimensionInfo provider) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Citylines must initialize on the server thread");
        }
        readRecord();
        resolveInMemory(provider);
    }

    private void readRecord() {
        if (recordRead) {
            return;
        }
        try {
            java.nio.file.Path directory = dimensionDirectory(level);
            boolean fileExists = java.nio.file.Files.exists(directory.resolve("data")
                    .resolve(CityGroundState.NAME + ".dat"));
            CityGroundState record = CityGroundState.get(level);
            persistedIdentity = record.identity();
            persistedGround = record.ground();
            if (!record.valid() || (fileExists && persistedIdentity == null)) {
                breakContract("unreadable or unsupported current generation record");
            }
            if (persistedIdentity == null) {
                regionState = regionState(directory, Level.OVERWORLD.equals(level.dimension()));
            }
        } catch (RuntimeException | LinkageError exception) {
            breakContract("cannot read generation inputs: " + exception);
        }
        recordRead = true;
    }

    /** {@code <dimension>}, i.e. the folder that owns this dimension's {@code region} directory. */
    private static java.nio.file.Path dimensionDirectory(ServerLevel level) {
        java.nio.file.Path root = level.getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        return root.resolve(dimensionFolder(level.dimension()));
    }

    /**
     * The dimension's folder relative to the world root, mirroring vanilla's own storage layout
     * ({@code LevelStorageSource.getDimensionPath}): the vanilla nether and end use the standard
     * {@code DIM-1} / {@code DIM1} folders, everything else lives under {@code dimensions/<ns>/<path>}.
     *
     * <p>Getting this wrong means looking at a folder that does not exist and declaring an existing
     * dimension "provably empty" — which is exactly the activation the gate must prevent. Kept static and
     * pure so the rule is unit-tested.
     */
    static String dimensionFolder(ResourceKey<Level> key) {
        return dimensionFolder(key.location().toString());
    }

    /** The same rule as a pure string function, so it can be unit-tested without a running game. */
    static String dimensionFolder(String dimensionId) {
        if ("minecraft:overworld".equals(dimensionId)) {
            return "";
        }
        if ("minecraft:the_nether".equals(dimensionId)) {
            return "DIM-1";
        }
        if ("minecraft:the_end".equals(dimensionId)) {
            return "DIM1";
        }
        int colon = dimensionId.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : dimensionId.substring(0, colon);
        String path = colon < 0 ? dimensionId : dimensionId.substring(colon + 1);
        return "dimensions/" + namespace + "/" + path;
    }

    /** What the dimension's storage proves about already-generated chunks. */
    enum RegionState {
        /** Provably nothing was generated here yet. */
        EMPTY,
        /** Chunk containers exist. */
        HAS_CHUNKS,
        /** Cannot be proven either way (unknown layout, unreadable, foreign files). */
        UNKNOWN
    }

    /**
     * Classifies a dimension folder for the activation gate (plan §8).
     *
     * <p>Only {@link RegionState#EMPTY} may activate the plane, so the rule is deliberately asymmetric:
     * a missing {@code region/} folder is <b>not</b> taken as proof of emptiness unless the folder looks
     * like an untouched vanilla dimension. Anything unexpected — a foreign file next to the vanilla
     * storage folders, an unreadable directory, {@code region} existing as a file — answers
     * {@link RegionState#UNKNOWN} and therefore refuses activation.
     *
     * <p>Kept static and {@link java.nio.file.Path} based so the rule is unit-testable.
     */
    static RegionState regionState(java.nio.file.Path dimensionDir, boolean worldRoot) {
        if (dimensionDir == null) {
            return RegionState.UNKNOWN;
        }
        java.nio.file.Path region = dimensionDir.resolve("region");
        if (java.nio.file.Files.isDirectory(region)) {
            return hasChunkContainers(region) ? RegionState.HAS_CHUNKS : RegionState.EMPTY;
        }
        if (java.nio.file.Files.exists(region)) {
            return RegionState.UNKNOWN;
        }
        if (!java.nio.file.Files.exists(dimensionDir)) {
            // The dimension folder itself was never created: nothing can have been generated.
            return RegionState.EMPTY;
        }
        if (worldRoot) {
            // The overworld's "dimension folder" is the world root, which always holds level.dat, the
            // datapack folders and so on. Those files say nothing about generated chunks, and vanilla
            // creates region/ lazily, so a missing region/ here really does mean "nothing generated yet".
            return RegionState.EMPTY;
        }
        try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(dimensionDir)) {
            if (!entries.allMatch(CityGroundBinding::isVanillaStorageEntry)) {
                return RegionState.UNKNOWN;
            }
        } catch (java.io.IOException exception) {
            return RegionState.UNKNOWN;
        }
        for (String name : new String[] {"entities", "poi"}) {
            java.nio.file.Path dir = dimensionDir.resolve(name);
            if (java.nio.file.Files.isDirectory(dir) && hasChunkContainers(dir)) {
                return RegionState.HAS_CHUNKS;
            }
        }
        return RegionState.EMPTY;
    }

    /** Vanilla per-dimension storage entries; anything else means we cannot judge this dimension. */
    private static boolean isVanillaStorageEntry(java.nio.file.Path entry) {
        String name = entry.getFileName().toString();
        return switch (name) {
            case "region", "entities", "poi", "data", "advancements", "playerdata", "stats",
                    "session.lock", "DIM-1", "DIM1" -> true;
            default -> false;
        };
    }

    /** True when a directory holds vanilla chunk containers ({@code .mca}, {@code .mcc}, {@code .mcr}). */
    private static boolean hasChunkContainers(java.nio.file.Path dir) {
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
            return files.anyMatch(file -> {
                String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return name.endsWith(".mca") || name.endsWith(".mcc") || name.endsWith(".mcr");
            });
        } catch (java.io.IOException exception) {
            return true;
        }
    }


    synchronized void resolveInMemory(IDimensionInfo provider) {
        if (state != State.NOT_READY) {
            return;
        }
        if (!recordRead || !level.getServer().isSameThread()) {
            reason = "generation inputs must finish initializing on the server thread";
            return;
        }
        boolean frozen = persistedIdentity != null;
        if (!CitylinesConfig.INSTANCE.enabled()) {
            if (frozen) {
                breakContract("enabled=false conflicts with this dimension's frozen generation rule");
            } else {
                nativeRule("Citylines is disabled");
            }
            return;
        }
        if (provider == null) {
            if (!frozen && mcjty.lostcities.setup.Config.getProfileForDimension(level.dimension()) == null) {
                nativeRule("dimension is not configured for Lost Cities");
            } else if (providerGaveUp) {
                breakContract("Lost Cities dimension provider is unavailable");
            } else {
                reason = "Lost Cities dimension provider is not ready";
            }
            return;
        }
        LostCityProfile profile = provider.getProfile();
        if (profile == null) {
            reason = "Lost Cities profile is not ready";
            return;
        }
        String[] capabilityReason = new String[1];
        State capability = capability(true, profile.isDefault(),
                profile.LANDSCAPE_TYPE == LandscapeType.DEFAULT, noiseGeneratorAvailable,
                profile.GROUNDLEVEL, minY, maxY, capabilityReason);
        if (capability != null) {
            decline(frozen, capabilityReason[0]);
            return;
        }
        CitylinesRoadState roads = CitylinesRoadState.of(provider);
        if (roads == null || !roads.isEligible()) {
            decline(frozen, roads == null ? "road inputs are unavailable" : roads.decisionReason());
            return;
        }
        if (!frozen && regionState != RegionState.EMPTY) {
            breakContract("cannot attach Citylines to a nonempty or unrecognized dimension; create a new world");
            return;
        }
        if (lc2hBlocksActivation() || lc2hHeightBranchEnabled()) {
            decline(frozen, "LC2H raw-height adapter is unavailable or its experimental height branch is enabled");
            return;
        }
        ground = profile.GROUNDLEVEL;
        String wanted;
        try {
            baseIdentity = identityFor(profile, ground, level.getSeed(), dimensionId)
                    + ";sampling=" + samplingFingerprint()
                    + ";roads=" + Long.toHexString(roads.fingerprint())
                    + ";assets=" + GenerationFingerprint.assets(level);
            wanted = baseIdentity + ";ec=" + extractionIdentity();
        } catch (RuntimeException | LinkageError exception) {
            breakContract("cannot freeze generation inputs: " + exception);
            return;
        }
        // Raw prechecks must not start LC2H's competing density field or consume corrected heights.
        rawGatesEnabled = true;
        String extractionFailure = extractionCitiesFailure(provider, profile);
        if (state == State.BROKEN) {
            return;
        }
        if (extractionFailure != null) {
            decline(frozen, "ExtractionCities precondition failed: " + extractionFailure);
            return;
        }
        if (frozen) {
            if (persistedGround != ground || !identityMatches(persistedIdentity, wanted)) {
                breakContract("current inputs differ from this dimension's frozen generation rule");
                return;
            }
        } else {
            try {
                CityGroundState record = CityGroundState.get(level);
                record.setIdentity(wanted, ground);
                record.saveChecked(level);
            } catch (RuntimeException | LinkageError exception) {
                breakContract("cannot persist the generation rule: " + exception);
                return;
            }
        }
        identity = wanted;
        persistedIdentity = wanted;
        persistedGround = ground;
        reason = "uniform city ground at " + ground + "; TLC owns city-edge terrain";
        state = State.READY;
        CitylinesMod.LOGGER.info("[citylines] generation READY for {}: {}", dimensionId, reason);
    }

    private void decline(boolean frozen, String why) {
        if (frozen) {
            breakContract(why);
        } else {
            nativeRule(why);
        }
    }

    private void nativeRule(String why) {
        rawGatesEnabled = false;
        reason = why;
        state = State.NATIVE;
        CitylinesMod.LOGGER.info("[citylines] native generation for {}: {}", dimensionId, why);
    }

    private String extractionIdentity() {
        return ModList.get().isLoaded("extractioncities") ? ExtractionCitiesDomainCheck.identity(level) : "absent";
    }

    public void acceptExtractionIdentity(String fingerprint) {
        if (!isReady() || baseIdentity == null) {
            throw new IllegalStateException("Generation binding is not ready for an override update");
        }
        String updated = baseIdentity + ";ec=" + fingerprint;
        try {
            CityGroundState record = CityGroundState.get(level);
            record.setIdentity(updated, ground);
            record.saveChecked(level);
        } catch (RuntimeException exception) {
            breakContract("cannot persist validated ExtractionCities overrides: " + exception);
            throw exception;
        }
        persistedIdentity = updated;
        identity = updated;
    }

    private String extractionCitiesFailure(IDimensionInfo provider, LostCityProfile profile) {
        if (!ModList.get().isLoaded("extractioncities")) {
            return null;
        }
        try {
            ExtractionCitiesDomainCheck.Result result = ExtractionCitiesDomainCheck.check(level, provider, profile);
            return result.ok() ? null : result.reason();
        } catch (RuntimeException | LinkageError exception) {
            return "ExtractionCities check unavailable (" + exception + ")";
        }
    }

    static State capability(boolean enabled, boolean profileIsDefault, boolean landscapeIsDefault,
            boolean noiseGenerator, int plane, int minY, int maxY, String[] reasonOut) {
        if (!enabled) {
            reasonOut[0] = "Citylines is disabled";
        } else if (!profileIsDefault || !landscapeIsDefault) {
            reasonOut[0] = "landscape is not DEFAULT";
        } else if (!noiseGenerator) {
            reasonOut[0] = "generator is not a NoiseBasedChunkGenerator";
        } else if (plane <= minY + 1 || plane >= maxY - 1) {
            reasonOut[0] = "GROUNDLEVEL is outside the build range";
        } else {
            reasonOut[0] = "capable";
            return null;
        }
        return State.NATIVE;
    }

    static String identityFor(LostCityProfile profile, int plane,
            long seed, String dimensionId) {
        return "citylines-1"
                + ";g=" + plane
                + ";profile=" + profile.getName()
                + ";landscape=" + profile.LANDSCAPE_TYPE
                + ";worldstyle=" + profile.getWorldStyle()
                + ";citystyle=" + profile.CITY_STYLE_THRESHOLD + "/" + profile.CITY_STYLE_ALTERNATIVE
                + ";chance=" + profile.CITY_CHANCE
                + ";minradius=" + profile.CITY_MINRADIUS
                + ";maxradius=" + profile.CITY_MAXRADIUS
                + ";perlin=" + profile.CITY_PERLIN_SCALE + "/" + profile.CITY_PERLIN_INNERSCALE
                + "/" + profile.CITY_PERLIN_OFFSET
                + ";threshold=" + profile.CITY_THRESHOLD
                + ";minh=" + profile.CITY_MINHEIGHT
                + ";maxh=" + profile.CITY_MAXHEIGHT
                + ";spawn=" + profile.CITY_SPAWN_DISTANCE1 + "/" + profile.CITY_SPAWN_MULTIPLIER1
                + "/" + profile.CITY_SPAWN_DISTANCE2 + "/" + profile.CITY_SPAWN_MULTIPLIER2
                + ";seed=" + seed
                + ";dim=" + dimensionId;
    }


    private static String samplingFingerprint() {
        return mcjty.lostcities.setup.Config.OPTIMIZED_HEIGHTMAP.get()
                + "/" + mcjty.lostcities.setup.Config.HEIGHT_SAMPLE_SIZE.get();
    }

    static boolean identityMatches(String persisted, String wanted) {
        return persisted != null && !persisted.isBlank() && persisted.equals(wanted);
    }

    private static boolean lc2hHeightBranchEnabled() {
        return ModList.get().isLoaded("lc2h") && Boolean.getBoolean("lc2h.heightBranch.enabled");
    }

    private static boolean lc2hBlocksActivation() {
        return ModList.get().isLoaded("lc2h") && !Lc2hRawHeightAdapter.class.isAssignableFrom(
                mcjty.lostcities.worldgen.lost.City.class);
    }
}
