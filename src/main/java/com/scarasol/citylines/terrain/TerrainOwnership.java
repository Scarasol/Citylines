package com.scarasol.citylines.terrain;

import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Shared dimension-level ownership. Never samples terrain or initializes a binding on a worker. */
public final class TerrainOwnership {
    private TerrainOwnership() {
    }

    public static boolean isManaged(IDimensionInfo provider) {
        return provider != null && isManaged(provider.getType());
    }

    public static boolean isManaged(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return false;
        }
        CityGroundBinding binding = TerrainFlattening.bindingFor(dimension);
        if (binding == null) {
            throw new IllegalStateException("Citylines dimension has not loaded: " + dimension.location());
        }
        return binding.ownsTerrain();
    }
}
