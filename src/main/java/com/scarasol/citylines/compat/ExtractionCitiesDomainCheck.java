package com.scarasol.citylines.compat;

import com.scarasol.extractioncities.world.level.dimension.DynamicDimensionRecord;
import com.scarasol.extractioncities.server.level.DynamicDimensionManager;
import com.scarasol.extractioncities.world.level.dimension.LostCityBuildingOverride;
import com.scarasol.extractioncities.world.level.dimension.LostCityChunkPos;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.City;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Optional;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.scarasol.citylines.terrain.CityGroundBinding;
import com.scarasol.citylines.terrain.ExtractionCitiesMutationAdapter;
import com.scarasol.citylines.terrain.GenerationFingerprint;
import com.scarasol.citylines.terrain.TerrainFlattening;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import java.util.Comparator;

/**
 * ExtractionCities precondition for the uniform plane: every chunk ExtractionCities forces to be a city
 * must lie inside the domain the plane actually covers (plan §5.3).
 *
 * <h2>Why this is needed</h2>
 *
 * ExtractionCities can force {@code characteristics.isCity = true} for a limited set of building
 * footprints ({@code TlcBuildingOverrides}), but the plane decides per chunk from Lost Cities' own city
 * factor. A forced chunk that the factor does not accept would keep native banded ground while the rest
 * of the dimension sits on the plane — exactly the seam this feature exists to remove — so activation is
 * refused instead.
 *
 * <h2>Why it cannot ask Lost Cities</h2>
 *
 * ExtractionCities rewrites the final {@code BuildingInfo} characteristics, so asking Lost Cities "is
 * this a city?" would just read back the forced answer. The domain is therefore evaluated with the same
 * criterion the plane itself uses: {@code City.getCityFactor(coord, provider, profile)} above the
 * profile threshold.
 *
 * <h2>Isolation</h2>
 *
 * Every ExtractionCities type is touched only from this class, and callers reach it only after
 * {@code ModList.isLoaded("extractioncities")}: with the mod absent the class is never loaded and its
 * {@code NoClassDefFoundError} can never fire. The footprint data is public API
 * ({@code DynamicDimensionManager.getRecord} → {@code lostCityBuildingOverrides()}), which is populated
 * before the {@code LevelEvent.Load} ExtractionCities posts for a new or restored dimension.
 */
public final class ExtractionCitiesDomainCheck {

    /** Chunk footprint visits allowed per dimension; more than this cannot be verified in bounded time. */
    private static final int MAX_VISITS = 65_536;

    private ExtractionCitiesDomainCheck() {
    }

    /**
     * @param ok     true when activation may proceed
     * @param reason human-readable explanation, always set
     */
    public record Result(boolean ok, String reason) {
    }

    /** Checks one dimension; never throws for API-shape problems, it reports them. */
    public static Result check(ServerLevel level, IDimensionInfo provider, LostCityProfile profile) {
        if (!ExtractionCitiesMutationAdapter.class.isAssignableFrom(DynamicDimensionManager.class)) {
            return new Result(false, "ExtractionCities mutation guard did not apply");
        }
        ResourceLocation dimension = level.dimension().location();
        Optional<DynamicDimensionRecord> found;
        try {
            found = DynamicDimensionManager.getRecord(dimension);
        } catch (RuntimeException | LinkageError exception) {
            // Cannot enumerate the overrides -> cannot prove the footprints are covered.
            return new Result(false, "cannot read the ExtractionCities record (" + exception + ")");
        }
        if (found.isEmpty()) {
            if ("extractioncities".equals(dimension.getNamespace())) {
                return new Result(false, "ExtractionCities dimension without a readable record");
            }
            // Not an ExtractionCities dimension: it cannot force chunks here.
            return new Result(true, "not an ExtractionCities dimension");
        }
        DynamicDimensionRecord record = found.get();
        if (!record.generateLostCities()) {
            return new Result(true, "ExtractionCities generates no Lost Cities content here");
        }
        List<LostCityBuildingOverride> overrides = record.lostCityBuildingOverrides();
        return checkOverrides(level, provider, profile, overrides);
    }

    public static Result checkOverrides(ServerLevel level, IDimensionInfo provider, LostCityProfile profile,
                                        List<LostCityBuildingOverride> overrides) {
        if (overrides.isEmpty()) {
            return new Result(true, "no forced building footprints");
        }
        int[] visits = {0};
        for (LostCityBuildingOverride override : overrides) {
            long endX = (long) override.anchorX() + override.width();
            long endZ = (long) override.anchorZ() + override.height();
            if (endX > Integer.MAX_VALUE || endZ > Integer.MAX_VALUE
                    || (long) override.width() * override.height() > MAX_VISITS - visits[0]) {
                return new Result(false, "forced footprint exceeds the finite domain-check budget");
            }
            for (int x = override.anchorX(); x < endX; x++) {
                for (int z = override.anchorZ(); z < endZ; z++) {
                    Result failure = visit(level, provider, profile, x, z, visits);
                    if (failure != null) {
                        return failure;
                    }
                }
            }
            for (LostCityChunkPos suppressed : override.suppressedChunks()) {
                Result failure = visit(level, provider, profile, suppressed.x(), suppressed.z(), visits);
                if (failure != null) {
                    return failure;
                }
            }
        }
        return new Result(true, overrides.size() + " forced footprint(s) inside the plane domain");
    }

    public static String identity(ServerLevel level) {
        return fingerprint(DynamicDimensionManager.getRecord(level.dimension().location())
                .map(DynamicDimensionRecord::lostCityBuildingOverrides).orElse(List.of()));
    }

    public static String fingerprint(List<LostCityBuildingOverride> overrides) {
        JsonArray entries = new JsonArray();
        for (LostCityBuildingOverride override : overrides) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", override.anchorX());
            entry.addProperty("z", override.anchorZ());
            entry.addProperty("building", override.buildingId().toString());
            entry.addProperty("type", override.type().name());
            entry.addProperty("width", override.width());
            entry.addProperty("height", override.height());
            JsonArray suppressed = new JsonArray();
            override.suppressedChunks().stream().sorted(Comparator.comparingInt(LostCityChunkPos::x)
                    .thenComparingInt(LostCityChunkPos::z)).forEach(chunk -> {
                        JsonArray position = new JsonArray();
                        position.add(chunk.x());
                        position.add(chunk.z());
                        suppressed.add(position);
                    });
            entry.add("suppressed", suppressed);
            entries.add(entry);
        }
        return GenerationFingerprint.digest(entries);
    }

    /** Runs inside EC's existing serialized mutation, before it publishes or saves the new record. */
    public static void beforeUpdate(MinecraftServer server, ResourceLocation dimension,
                                    List<LostCityBuildingOverride> overrides) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) {
            return; // An unloaded dimension is checked at its next activation.
        }
        CityGroundBinding binding = TerrainFlattening.bindingFor(level);
        if (binding == null || binding.state() == CityGroundBinding.State.NATIVE) {
            return;
        }
        if (!server.isSameThread() || !binding.isReady()) {
            throw new IllegalStateException("Uniform city overrides require a ready binding on the server thread");
        }
        IDimensionInfo provider = TerrainFlattening.providerFor(level);
        if (provider == null || provider.getProfile() == null) {
            throw new IllegalStateException("Uniform city provider is unavailable");
        }
        Result result = checkOverrides(level, provider, provider.getProfile(), overrides);
        if (!result.ok()) {
            throw new IllegalArgumentException("Citylines rejected the override before publication: " + result.reason());
        }
    }

    /** Accepted in-domain edits are explicit changes; persist their identity for the next load. */
    public static void afterUpdate(MinecraftServer server, DynamicDimensionRecord record) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, record.id()));
        CityGroundBinding binding = level == null ? null : TerrainFlattening.bindingFor(level);
        if (binding != null && binding.isReady()) {
            binding.acceptExtractionIdentity(fingerprint(record.lostCityBuildingOverrides()));
        }
    }

    /** These inputs change the frozen domain itself, unlike a validated in-domain building edit. */
    public static void beforeRuleUpdate(MinecraftServer server, ResourceLocation dimension,
                                         Object requested, String input) {
        DynamicDimensionRecord record = DynamicDimensionManager.getRecord(dimension).orElse(null);
        if (record == null) {
            return; // EC reports the unknown dimension itself.
        }
        Object existing = switch (input) {
            case "profile" -> record.lostCitiesProfile();
            case "worldstyle" -> record.lostCitiesWorldStyle();
            case "enabled" -> record.generateLostCities();
            default -> throw new IllegalArgumentException("Unknown generation input " + input);
        };
        if (existing.equals(requested)) {
            return;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        CityGroundBinding binding = level == null ? null : TerrainFlattening.bindingFor(level);
        if (binding != null && (binding.isReady() || binding.state() == CityGroundBinding.State.BROKEN)) {
            throw new IllegalStateException("Citylines froze " + input + " for " + dimension
                    + "; use a new dimension to change the generation rule");
        }
    }

    /** @return null when the chunk is inside the domain, otherwise the failure to report */
    private static Result visit(ServerLevel level, IDimensionInfo provider, LostCityProfile profile,
                                int chunkX, int chunkZ, int[] visits) {
        if (++visits[0] > MAX_VISITS) {
            return new Result(false, "more than " + MAX_VISITS + " forced chunks cannot be verified");
        }
        ChunkCoord coord = new ChunkCoord(level.dimension(), chunkX, chunkZ);
        float factor;
        try {
            factor = City.getCityFactor(coord, provider, profile);
        } catch (RuntimeException | LinkageError exception) {
            return new Result(false, "cannot evaluate the city mask at " + chunkX + "," + chunkZ
                    + " (" + exception + ")");
        }
        if (factor <= profile.CITY_THRESHOLD) {
            return new Result(false, "forced city chunk " + chunkX + "," + chunkZ
                    + " is outside the plane domain (factor " + factor + " <= " + profile.CITY_THRESHOLD + ")");
        }
        return null;
    }
}
