package com.scarasol.citylines.terrain;

import com.scarasol.citylines.CitylinesMod;
import com.scarasol.citylines.config.CitylinesConfig;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Uniform city ground with raw sampling isolated from effective terrain. */
public final class TerrainFlattening {

    /** Returned by {@link #flattenedLevel} when the chunk is not flattened. */
    public static final int NO_LEVEL = Integer.MIN_VALUE;
    /** Returned by the internal decision when the chunk is not flattened. */
    private static final int NO_DECISION = Integer.MIN_VALUE;

    private static final int CACHE_LIMIT = 200_000;

    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();
    private static final Map<ChunkGenerator, LevelFacts> BY_GENERATOR = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, LevelFacts> BY_LEVEL = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, LevelFacts> BY_DIMENSION = new ConcurrentHashMap<>();
    private static final AtomicInteger DEBUG_BUDGET = new AtomicInteger(300);
    private static final AtomicBoolean UNKNOWN_RAW_WARNED = new AtomicBoolean();

    private TerrainFlattening() {
    }

    // ------------------------------------------------------------------ lifecycle

    /** Remembers a level's generator so the noise stage can find its dimension info. */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelFacts facts = registerLevel(level);
            if (Level.OVERWORLD.equals(level.dimension())
                    && !level.getServer().getWorldData().overworldData().isInitialized()) {
                // TLC's CreateSpawnPosition handler has not initialized its street modes yet.
                return;
            }
            // Server thread: reading the persisted city-ground record here is what keeps the role of a
            // dimension stable across sessions (plan §8). An unresolved profile stays NOT_READY;
            // a worker may not initialize storage and refuses generation until server-side resolution.
            facts.binding.resolveAtLoad(facts.provider());
        }
    }

    /**
     * The game itself created this dimension: freeze the city-ground rule before the first chunk is
     * generated.
     *
     * Freshness still comes from dimension storage, not the event itself. Level.Load also resolves EC
     * dimensions, which do not emit CreateSpawnPosition.
     */
    public static void onSpawnProvider(ServerLevel level, IDimensionInfo provider) {
        LevelFacts facts = registerLevel(level);
        facts.resolveProvider(provider);
        facts.binding.resolveAtLoad(facts.provider());
        CitylinesMod.LOGGER.info("[citylines] city ground for {}: {} ({})",
                level.dimension().location(), facts.binding.state(), facts.binding.reason());
    }

    private static LevelFacts registerLevel(ServerLevel level) {
        LevelFacts facts = BY_LEVEL.computeIfAbsent(level, LevelFacts::new);
        BY_GENERATOR.put(facts.generator, facts);
        BY_DIMENSION.put(level.dimension(), facts);
        return facts;
    }

    /** Drops the per-level facts (and their caches) when a level unloads. */
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelFacts facts = BY_LEVEL.remove(level);
            if (facts != null) {
                BY_GENERATOR.remove(facts.generator);
                BY_DIMENSION.remove(level.dimension(), facts);
            }
        }
    }

    /** True when this dimension is generating on the uniform plane (used by the raw-height adapters). */
    public static boolean isUniformActive(ResourceKey<Level> dimension) {
        LevelFacts facts = BY_DIMENSION.get(dimension);
        return facts != null && facts.binding != null && facts.binding.expectsPlane();
    }

    // ------------------------------------------------------------------ frames

    /** Restores the exact previous frame even when a nested column or fill throws. */
    public static <T> T withContext(NoiseBasedChunkGenerator generator, boolean sampling,
                                    java.util.function.Supplier<T> operation) {
        Frame previous = FRAME.get();
        push(new Frame(generator == null ? null : factsOfGenerator(generator), sampling));
        try {
            return operation.get();
        } finally {
            if (previous == null) {
                FRAME.remove();
            } else {
                FRAME.set(previous);
            }
        }
    }

    static Boolean samplingContext() {
        Frame frame = FRAME.get();
        return frame == null ? null : frame.sampling;
    }

    private static void push(Frame frame) {
        FRAME.set(frame);
    }

    private static LevelFacts factsOfGenerator(NoiseBasedChunkGenerator generator) {
        LevelFacts facts = BY_GENERATOR.get(generator);
        if (facts != null) {
            return facts;
        }
        for (LevelFacts candidate : BY_LEVEL.values()) {
            if (candidate.generator == generator) {
                BY_GENERATOR.put(generator, candidate);
                return candidate;
            }
        }
        // Fallback for levels the Load event never handed us (it is fired before a level's chunk
        // source necessarily exists): ask the running server which level owns this generator.
        // Chunk generation only ever happens while the server is running, so this is safe.
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() != generator) {
                continue;
            }
            LevelFacts created = new LevelFacts(level);
            LevelFacts existing = BY_LEVEL.putIfAbsent(level, created);
            facts = existing != null ? existing : created;
            BY_GENERATOR.put(generator, facts);
            BY_DIMENSION.put(level.dimension(), facts);
            debug("resolved {} from the running server", level.dimension().location());
            return facts;
        }
        return null;
    }

    // ------------------------------------------------------------------ override

    /**
     * Called at the head of {@code NoiseChunk.getInterpolatedState()}.
     *
     * @return the state to force (air above the target ground), or null to run the original
     */
    public static BlockState overrideHead(NoiseChunk chunk) {
        Frame frame = FRAME.get();
        if (frame == null || frame.sampling || frame.facts == null) {
            return null;
        }
        int ground = frame.groundFor(chunk.blockX() >> 4, chunk.blockZ() >> 4);
        if (ground == NO_DECISION) {
            return null;
        }
        return chunk.blockY() > ground ? Blocks.AIR.defaultBlockState() : null;
    }

    /**
     * Called at the return of {@code NoiseChunk.getInterpolatedState()}.
     *
     * @return the state to force (solid at or below the target ground), or null to keep the original
     */
    public static BlockState overrideReturn(NoiseChunk chunk, BlockState original) {
        Frame frame = FRAME.get();
        if (frame == null || frame.sampling || frame.facts == null) {
            return null;
        }
        int ground = frame.groundFor(chunk.blockX() >> 4, chunk.blockZ() >> 4);
        if (ground == NO_DECISION) {
            return null;
        }
        if (chunk.blockY() > ground) {
            return Blocks.AIR.defaultBlockState();
        }
        return original == null || original.isAir() ? frame.facts.defaultBlock : null;
    }

    // ------------------------------------------------------------------ level hook

    /**
     * The city level that matches the flattened terrain, or {@link #NO_LEVEL}.
     *
     * <p>This is what makes the feature independent of every profile's band thresholds: returning
     * the level whose ground equals the terrain height we generated makes Lost Cities' own levelling
     * pass a no-op, with no profile data changed.
     */
    public static int flattenedLevel(ChunkCoord coord, IDimensionInfo provider) {
        if (provider == null) {
            return NO_LEVEL;
        }
        LevelFacts facts = factsOfProvider(provider);
        if (facts == null) {
            return NO_LEVEL;
        }
        int ground = decision(facts, coord.chunkX(), coord.chunkZ());
        if (ground == NO_DECISION) {
            return NO_LEVEL;
        }
        return 0;
    }

    private static LevelFacts factsOfProvider(IDimensionInfo provider) {
        if (provider.getWorld() == null || provider.getWorld().getLevel() == null) {
            return null;
        }
        ServerLevel level = provider.getWorld().getLevel();
        LevelFacts facts = BY_LEVEL.get(level);
        if (facts == null) {
            // The level was not seen by LevelEvent.Load (or loaded before this mod): build the
            // facts now and reuse them, so both call sites keep agreeing on the decision.
            facts = new LevelFacts(level);
            LevelFacts existing = BY_LEVEL.putIfAbsent(level, facts);
            if (existing != null) {
                facts = existing;
            } else {
                BY_GENERATOR.put(facts.generator, facts);
            }
        }
        BY_DIMENSION.put(level.dimension(), facts);
        facts.resolveProvider(provider);
        return facts;
    }

    // ------------------------------------------------------------------ decision

    private static int decision(LevelFacts facts, int chunkX, int chunkZ) {
        if (facts == null) {
            return NO_DECISION;
        }
        IDimensionInfo provider = facts.provider();
        if (facts.binding.state() == CityGroundBinding.State.NOT_READY) {
            // Only a server-thread caller may complete initialization; workers remain NOT_READY.
            facts.binding.resolveInMemory(provider);
            if (facts.binding.state() == CityGroundBinding.State.NOT_READY) {
                // The contract for this dimension is still unknown, so a chunk generated now could end
                // up on natural terrain next to a plane. Refuse instead of writing the wrong ground
                // (plan §4.2: "禁止据此生成或缓存永久回退").
                refuse(facts.binding, "the city-ground binding is still NOT_READY");
            }
        }
        if (facts.binding.state() == CityGroundBinding.State.BROKEN) {
            refuse(facts.binding, facts.binding.reason());
        }
        if (facts.noiseGenerator == null || provider == null || provider.getWorld() == null) {
            if (facts.binding.isReady()) {
                facts.binding.breakContract("the frozen plane lost its generator or dimension provider");
                refuse(facts.binding, facts.binding.reason());
            }
            return NO_DECISION;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Integer cached = facts.decisionCache.get(key);
        if (cached != null) {
            return cached;
        }
        int ground = computeDecision(facts, provider, new ChunkCoord(provider.getType(), chunkX, chunkZ));
        if (facts.decisionCache.size() > CACHE_LIMIT) {
            facts.decisionCache.clear();
        }
        facts.decisionCache.put(key, ground);
        return ground;
    }

    private static int computeDecision(LevelFacts facts, IDimensionInfo provider, ChunkCoord coord) {
        CityGroundBinding binding = facts.binding;
        if (binding.state() == CityGroundBinding.State.NATIVE) {
            return NO_DECISION;
        }
        if (!binding.isReady()) {
            refuse(binding, binding.reason());
        }
        LostCityProfile profile = profileFor(provider, coord);
        if (profile == null || !profile.isDefault()) {
            binding.breakContract("the active dimension lost its DEFAULT profile");
            refuse(binding, binding.reason());
        }
        float factor = City.getCityFactor(coord, provider, profile);
        if (binding.state() == CityGroundBinding.State.BROKEN) {
            refuse(binding, binding.reason());
        }
        return factor > profile.CITY_THRESHOLD ? binding.ground() : NO_DECISION;
    }

    /**
     * Fails loudly instead of generating a chunk whose ground would contradict the dimension's rule.
     *
     * <p>The plan is explicit that a dimension which asked for (or was frozen to) the uniform plane must
     * not silently continue on natural terrain: the two kinds of ground side by side are the seam the
     * whole feature exists to remove. The message names the recovery options so a crash report is
     * actionable.
     */
    private static void refuse(CityGroundBinding binding, String why) {
        throw new IllegalStateException("[citylines] city-ground contract violated for "
                + binding.dimensionId() + ": " + why
                + ". Chunk generation is refused so natural terrain is not written next to the uniform"
                + " plane. Restore the compatible mod/configuration used for this world, or create a new"
                + " world.");
    }

    /**
     * One-shot warning for a height read that has no frozen raw snapshot.
     *
     * <p>Used by the road water facts, which cannot refuse to answer: an unknown column is reported once
     * and treated conservatively by the caller. The uniform-plane gate never needs this — it rejects.
     */
    public static void warnUnknownRawHeight(ChunkCoord coord) {
        if (UNKNOWN_RAW_WARNED.compareAndSet(false, true)) {
            CitylinesMod.LOGGER.warn("[citylines] no raw height snapshot for chunk {},{}; treating the"
                    + " column as water. Further occurrences are not logged.",
                    coord.chunkX(), coord.chunkZ());
        }
    }

    /**
     * Set by the raw-height adapters when a city height gate needed a raw terrain height and none
     * existed. The mask result for that chunk is then worthless — it says "not a city" because an input
     * was missing, not because the terrain is unsuitable — so the decision must refuse to generate
     * instead of caching "not flattened" (plan §4.4: a missing input must not land a wrong chunk).
     */
    /**
     * Stops the factor query before LC2H can publish a fabricated non-city result.
     *
     * <p>The binding also stays BROKEN if an upstream caller catches the exception. There is no growing
     * failed-chunk set and no cache-eviction path that can erase the failure.
     */
    public static void noteUnknownRawHeight(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        LevelFacts facts = BY_DIMENSION.get(dimension);
        if (facts == null) {
            throw new IllegalStateException("Missing uniform binding for " + dimension.location());
        }
        facts.binding.breakContract("missing raw height at " + chunkX + "," + chunkZ);
        refuse(facts.binding, facts.binding.reason());
    }

    public static CityGroundBinding bindingFor(ResourceKey<Level> dimension) {
        LevelFacts facts = BY_DIMENSION.get(dimension);
        return facts == null ? null : facts.binding;
    }

    public static CityGroundBinding bindingFor(ServerLevel level) {
        LevelFacts facts = BY_LEVEL.get(level);
        return facts == null ? null : facts.binding;
    }

    public static IDimensionInfo providerFor(ServerLevel level) {
        LevelFacts facts = BY_LEVEL.get(level);
        return facts == null ? null : facts.provider();
    }

    /** Diagnostics for the flattening decision, gated by the shared debug switch. */
    private static void debug(String message, Object... args) {
        if (CitylinesConfig.INSTANCE.debugLogging() && DEBUG_BUDGET.getAndDecrement() > 0) {
            CitylinesMod.LOGGER.info("[citylines] flattening " + message, args);
        }
    }

    private static LostCityProfile profileFor(IDimensionInfo provider, ChunkCoord coord) {
        LostCityProfile profile = BuildingInfo.getProfile(coord, provider);
        return profile != null ? profile : provider.getProfile();
    }

    // ------------------------------------------------------------------ facts

    /** Per-dimension facts plus the flattening caches. */
    static final class LevelFacts {
        final ServerLevel level;
        final ChunkGenerator generator;
        final NoiseBasedChunkGenerator noiseGenerator;
        final BlockState defaultBlock;
        final int minY;
        final int maxY;
        final Map<Long, Integer> decisionCache = new ConcurrentHashMap<>();
        /** The dimension's frozen city-ground rule (plan §4.1). */
        final CityGroundBinding binding;

        private static final int MAX_PROVIDER_ATTEMPTS = 4096;
        private volatile IDimensionInfo provider;
        private volatile boolean providerResolved;
        private int providerAttempts;
        private boolean providerAbsenceLogged;

        LevelFacts(ServerLevel level) {
            this.level = level;
            this.generator = level.getChunkSource().getGenerator();
            this.noiseGenerator = generator instanceof NoiseBasedChunkGenerator noise ? noise : null;
            BlockState block = Blocks.STONE.defaultBlockState();
            if (noiseGenerator != null) {
                NoiseGeneratorSettings settings = noiseGenerator.generatorSettings().value();
                block = settings.defaultBlock();
            }
            this.defaultBlock = block;
            this.minY = level.getMinBuildHeight();
            this.maxY = level.getMaxBuildHeight();
            this.binding = new CityGroundBinding(level, minY, maxY, noiseGenerator != null);
        }

        IDimensionInfo provider() {
            if (!providerResolved) {
                resolveProvider(null);
            }
            return provider;
        }

        void resolveProvider(IDimensionInfo known) {
            if (providerResolved) {
                return;
            }
            synchronized (this) {
                if (providerResolved) {
                    return;
                }
                IDimensionInfo resolved = known;
                if (resolved == null) {
                    if (++providerAttempts > MAX_PROVIDER_ATTEMPTS) {
                        // Give up after a bounded number of tries: a dimension that still has no Lost
                        // Cities info after this many decisions is not managed by Lost Cities.
                        providerResolved = true;
                        provider = null;
                        binding.markProviderAbsent();
                        return;
                    }
                    try {
                        resolved = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
                    } catch (RuntimeException exception) {
                        if (!providerAbsenceLogged) {
                            providerAbsenceLogged = true;
                            CitylinesMod.LOGGER.debug(
                                    "[citylines] no Lost Cities dimension info for {}: {}",
                                    level.dimension().location(), exception.toString());
                        }
                    }
                }
                if (resolved == null) {
                    // Do NOT latch the absence: Lost Cities may still be creating its dimension info, and
                    // a latched null would permanently misclassify a dimension that is still initializing
                    // (and should have been activated).
                    provider = null;
                    return;
                }
                provider = resolved;
                providerResolved = true;
            }
        }

    }

    /** One thread's current position in the noise pipeline, plus a one-chunk decision memo. */
    private static final class Frame {
        final LevelFacts facts;
        final boolean sampling;
        long cachedKey = Long.MIN_VALUE;
        int cachedGround = NO_DECISION;

        Frame(LevelFacts facts, boolean sampling) {
            this.facts = facts;
            this.sampling = sampling;
        }

        int groundFor(int chunkX, int chunkZ) {
            long key = ChunkPos.asLong(chunkX, chunkZ);
            if (cachedKey == key) {
                return cachedGround;
            }
            int ground = decision(facts, chunkX, chunkZ);
            cachedKey = key;
            cachedGround = ground;
            return ground;
        }
    }
}
