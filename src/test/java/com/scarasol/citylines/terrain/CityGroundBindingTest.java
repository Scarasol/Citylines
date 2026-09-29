package com.scarasol.citylines.terrain;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityGroundBindingTest {
    private static CityGroundBinding.State capability(boolean enabled, boolean profileDefault,
            boolean landscapeDefault, boolean noiseGenerator, int plane) {
        String[] reason = new String[1];
        var state = CityGroundBinding.capability(enabled, profileDefault, landscapeDefault,
                noiseGenerator, plane, -64, 320, reason);
        assertNotNull(reason[0]);
        return state;
    }

    @Test
    void singleRuleCapabilityGate() {
        assertNull(capability(true, true, true, true, 71));
        assertEquals(CityGroundBinding.State.NATIVE, capability(false, true, true, true, 71));
        assertEquals(CityGroundBinding.State.NATIVE, capability(true, false, true, true, 71));
        assertEquals(CityGroundBinding.State.NATIVE, capability(true, true, false, true, 71));
        assertEquals(CityGroundBinding.State.NATIVE, capability(true, true, true, false, 71));
        assertEquals(CityGroundBinding.State.NATIVE, capability(true, true, true, true, -64));
        assertEquals(CityGroundBinding.State.NATIVE, capability(true, true, true, true, 319));
        assertNull(capability(true, true, true, true, -62));
        assertNull(capability(true, true, true, true, 318));
    }

    @Test
    void onlyCurrentRecordRoundTrips() {
        CityGroundState empty = new CityGroundState();
        assertNull(empty.identity());
        assertTrue(empty.valid());
        assertThrows(IllegalStateException.class, () -> empty.save(new CompoundTag()));
        empty.setIdentity("citylines-1;g=71;assets=abc", 71);
        CompoundTag tag = empty.save(new CompoundTag());
        assertEquals(java.util.Set.of("schema", "identity", "ground"), tag.getAllKeys());
        CityGroundState read = new CityGroundState(tag);
        assertTrue(read.valid());
        assertEquals(empty.identity(), read.identity());
        assertEquals(71, read.ground());
        tag.putInt("schema", 2);
        assertFalse(new CityGroundState(tag).valid());
        assertFalse(new CityGroundState(new CompoundTag()).valid());
        tag.putInt("schema", 1);
        tag.putString("ground", "71");
        assertFalse(new CityGroundState(tag).valid());
    }

    @Test
    void identityMatchesOnlyExactNonblankInput() {
        String known = "citylines-1;g=71;assets=abc";
        assertTrue(CityGroundBinding.identityMatches(known, known));
        assertFalse(CityGroundBinding.identityMatches(null, known));
        assertFalse(CityGroundBinding.identityMatches("", ""));
        assertFalse(CityGroundBinding.identityMatches(known, known + ";changed"));
    }

    @Test
    @DisplayName("the region gate only accepts a provably empty dimension")
    void regionGateNeedsAnEmptyStorage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        java.nio.file.Path dim = java.nio.file.Files.createDirectories(dir.resolve("world"));
        // No region folder yet, folder holds only vanilla storage entries -> provably empty.
        java.nio.file.Files.createDirectories(dim.resolve("data"));
        java.nio.file.Files.writeString(dim.resolve("session.lock"), "x");
        assertEquals(CityGroundBinding.RegionState.EMPTY, CityGroundBinding.regionState(dim, false));

        // A foreign file means we cannot judge this dimension's storage.
        java.nio.file.Files.writeString(dim.resolve("some-mod-store.bin"), "x");
        assertEquals(CityGroundBinding.RegionState.UNKNOWN, CityGroundBinding.regionState(dim, false));
        java.nio.file.Files.delete(dim.resolve("some-mod-store.bin"));

        // Any chunk container in region/ means chunks exist.
        java.nio.file.Path region = java.nio.file.Files.createDirectories(dim.resolve("region"));
        java.nio.file.Files.writeString(region.resolve("readme.txt"), "x");
        assertEquals(CityGroundBinding.RegionState.EMPTY, CityGroundBinding.regionState(dim, false));
        for (String name : new String[] {"r.0.0.mca", "r.0.0.mcc", "r.0.0.mcr"}) {
            java.nio.file.Path file = region.resolve(name);
            java.nio.file.Files.writeString(file, "x");
            assertEquals(CityGroundBinding.RegionState.HAS_CHUNKS, CityGroundBinding.regionState(dim, false),
                    name + " must count as generated");
            java.nio.file.Files.delete(file);
        }

        // A completely absent dimension folder is empty; an absent folder we cannot inspect is unknown.
        assertEquals(CityGroundBinding.RegionState.EMPTY,
                CityGroundBinding.regionState(dir.resolve("never-created"), false));
        assertEquals(CityGroundBinding.RegionState.UNKNOWN, CityGroundBinding.regionState(null, false));

        // region existing as a file (not a directory) proves nothing.
        java.nio.file.Files.delete(region.resolve("readme.txt"));
        java.nio.file.Files.delete(region);
        java.nio.file.Files.writeString(dim.resolve("region"), "x");
        assertEquals(CityGroundBinding.RegionState.UNKNOWN, CityGroundBinding.regionState(dim, false));

        // The world root (overworld) holds level.dat and datapacks; those are not chunk evidence and
        // vanilla creates region/ lazily, so a missing region/ there counts as empty.
        java.nio.file.Path root = java.nio.file.Files.createDirectories(dir.resolve("world-root"));
        java.nio.file.Files.writeString(root.resolve("level.dat"), "x");
        java.nio.file.Files.createDirectories(root.resolve("datapacks"));
        assertEquals(CityGroundBinding.RegionState.EMPTY, CityGroundBinding.regionState(root, true));
    }

    @Test
    @DisplayName("vanilla dimension folders are resolved the way vanilla stores them")
    void dimensionFoldersMatchVanillaLayout() {
        assertEquals("", CityGroundBinding.dimensionFolder("minecraft:overworld"));
        assertEquals("DIM-1", CityGroundBinding.dimensionFolder("minecraft:the_nether"));
        assertEquals("DIM1", CityGroundBinding.dimensionFolder("minecraft:the_end"));
        assertEquals("dimensions/extractioncities/extraction_city",
                CityGroundBinding.dimensionFolder("extractioncities:extraction_city"));
        assertEquals("dimensions/custom_mod/sky", CityGroundBinding.dimensionFolder("custom_mod:sky"));
    }

    @Test
    @DisplayName("the frozen identity covers every input that can move the city domain")
    void identityCoversMaskInputs() {
        mcjty.lostcities.config.LostCityProfile base = new mcjty.lostcities.config.LostCityProfile("probe", false);
        String reference = CityGroundBinding.identityFor(base,
                71, 42L, "minecraft:overworld");
        assertEquals(reference, CityGroundBinding.identityFor(new mcjty.lostcities.config.LostCityProfile("probe", false), 71, 42L, "minecraft:overworld"));

        mcjty.lostcities.config.LostCityProfile chance = new mcjty.lostcities.config.LostCityProfile("probe", false);
        chance.CITY_CHANCE = 0.02;
        mcjty.lostcities.config.LostCityProfile radius = new mcjty.lostcities.config.LostCityProfile("probe", false);
        radius.CITY_MAXRADIUS = 64;
        mcjty.lostcities.config.LostCityProfile perlin = new mcjty.lostcities.config.LostCityProfile("probe", false);
        perlin.CITY_PERLIN_SCALE = 5;
        mcjty.lostcities.config.LostCityProfile spawn = new mcjty.lostcities.config.LostCityProfile("probe", false);
        spawn.CITY_SPAWN_DISTANCE1 = 200;
        for (var changed : java.util.List.of(chance, radius, perlin, spawn)) {
            assertNotEquals(reference, CityGroundBinding.identityFor(
                    changed, 71, 42L, "minecraft:overworld"));
        }
        // ... and so does every input the plane itself depends on.
        assertNotEquals(reference, CityGroundBinding.identityFor(
                base, 77, 42L, "minecraft:overworld"));
        assertNotEquals(reference, CityGroundBinding.identityFor(
                base, 71, 43L, "minecraft:overworld"));
        assertNotEquals(reference, CityGroundBinding.identityFor(base, 71, 42L, "test:other"));
    }

}

