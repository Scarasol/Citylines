package com.scarasol.citylines.road.core;

/**
 * Frozen inputs consumed by the bridge overlay.
 *
 * <p>The implementation may adapt TLC, but the bridge planner itself only sees
 * order-independent facts and the already published V3 land graph. It never reads
 * generated blocks, {@code BuildingInfo}, or a neighbouring plan while holding a
 * caller-owned lock.
 */
public interface V3BridgeInput {

    ChunkFacts factsAt(int chunkX, int chunkZ);

    V3CellInfo roadAt(int chunkX, int chunkZ);
}
