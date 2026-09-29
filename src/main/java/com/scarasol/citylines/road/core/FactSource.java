package com.scarasol.citylines.road.core;

/**
 * The only source of chunk facts the planner may consult.
 *
 * <p>Implementations must be pure with respect to planning: the same coordinate
 * must always yield the same {@link ChunkFacts} for the lifetime of a plan, and
 * they must not call back into final Lost Cities state that depends on request
 * order (see the frozen input table in the design document).
 */
@FunctionalInterface
public interface FactSource {

    ChunkFacts factsAt(int chunkX, int chunkZ);

    /** Convenience for tests and synthetic cases. */
    static FactSource of(java.util.function.IntFunction<ChunkFacts> byPackedCoord, int size) {
        return (x, z) -> byPackedCoord.apply(z * size + x);
    }
}
