package com.scarasol.citylines.road.core;

/** Same coordinate must return the same frozen fact for the lifetime of a planning version. */
@FunctionalInterface
public interface V3FactSource {
    V3Facts factsAt(int chunkX, int chunkZ);
}
