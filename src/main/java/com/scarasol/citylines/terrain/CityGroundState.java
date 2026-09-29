package com.scarasol.citylines.terrain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * Per-dimension record of the city-ground rule a dimension was created with.
 *
 * A missing record alone never proves freshness. CityGroundBinding checks dimension storage before
 * activation at Level.Load or CreateSpawnPosition, including EC's dynamic dimensions.
 *
 * <p>The stored identity string pins the inputs the rule depends on (profile base, landscape type,
 * city gates, seed). A mismatch is reported and refused rather than resolved by guessing.
 */
public final class CityGroundState extends SavedData {

    /** File name inside the dimension's {@code data} folder. */
    public static final String NAME = "citylines_generation";
    private static final int SCHEMA = 1;
    private String identity;
    private int ground;
    private boolean valid = true;

    public CityGroundState() {
    }

    public CityGroundState(CompoundTag tag) {
        valid = tag.getInt("schema") == SCHEMA && tag.contains("identity", Tag.TAG_STRING)
                && tag.contains("ground", Tag.TAG_INT) && !tag.getString("identity").isBlank();
        if (valid) {
            identity = tag.getString("identity");
            ground = tag.getInt("ground");
        }
    }

    /** The record of the level's own dimension storage (server thread). */
    public static CityGroundState get(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(CityGroundState::new, CityGroundState::new, NAME);
    }

    synchronized String identity() {
        return identity;
    }

    synchronized int ground() {
        return ground;
    }

    synchronized void setIdentity(String value, int ground) {
        if (!valid || value == null || value.isBlank()) {
            throw new IllegalStateException("Cannot write an invalid Citylines generation record");
        }
        if (!value.equals(identity) || this.ground != ground) {
            identity = value;
            this.ground = ground;
            setDirty();
        }
    }

    /** SavedData.save logs and swallows I/O errors; activation needs an acknowledged durable write. */
    synchronized void saveChecked(ServerLevel level) {
        java.nio.file.Path root = level.getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT);
        java.nio.file.Path directory = root.resolve(CityGroundBinding.dimensionFolder(level.dimension()))
                .resolve("data");
        java.nio.file.Path temporary = null;
        try {
            java.nio.file.Files.createDirectories(directory);
            temporary = java.nio.file.Files.createTempFile(directory, NAME + "-", ".tmp");
            CompoundTag document = new CompoundTag();
            document.put("data", save(new CompoundTag()));
            NbtUtils.addCurrentDataVersion(document);
            NbtIo.writeCompressed(document, temporary.toFile());
            java.nio.file.Path target = directory.resolve(NAME + ".dat");
            try {
                java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            setDirty(false);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException("Cannot persist city ground for " + level.dimension(), exception);
        } finally {
            if (temporary != null) {
                try {
                    java.nio.file.Files.deleteIfExists(temporary);
                } catch (java.io.IOException ignored) {
                    // A failed cleanup does not make a failed identity write successful.
                }
            }
        }
    }

    synchronized boolean valid() {
        return valid;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (!valid || identity == null) {
            throw new IllegalStateException("Citylines generation record is not initialized");
        }
        tag.putInt("schema", SCHEMA);
        tag.putString("identity", identity);
        tag.putInt("ground", ground);
        return tag;
    }
}
