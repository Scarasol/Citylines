package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.CitylinesMod;
import com.scarasol.citylines.config.CitylinesConfig;
import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.V3CellInfo;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.worldgen.street.PlannedRoadType;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, opt-in diagnostics for the Citylines road-rights layer.
 *
 * <p>Everything here is a no-op unless {@code CitylinesConfig.debugLogging()} is on,
 * and the counters below are <b>diagnostic only</b>: they are never consulted by
 * generation, they cannot change any plan, and they carry no per-dimension state that
 * outlives the process. The counters exist so that a server log proves that Citylines road
 * classes were actually kept, that the wrapped rule really differs from Lost Cities'
 * TLC neighbour rule, and how the multi-building refusal fired — without adding
 * per-chunk noise to a normal run.
 */
public final class RoadDiagnostics {

    private static final int MAX_SAMPLE_CHUNKS = 4;
    private static final int SUMMARY_EVERY = 256;

    private static final Map<String, Counters> PER_DIMENSION = new ConcurrentHashMap<>();

    private RoadDiagnostics() {
    }

    private static Counters counters(String dimensionId) {
        return PER_DIMENSION.computeIfAbsent(dimensionId, key -> new Counters());
    }

    /**
     * One resolved chunk that the Citylines planner gave a road class to.
     *
     * <p>{@code nativeAnswer} is what Lost Cities' own rule would have returned for the same
     * inputs had the Citylines mask not been substituted, so {@code differsFromNative} counts the
     * chunks for which this injection actually changed the effective road class.
     *
     * @param nativeHasConnectedCityNeighbor the neighbour value Lost Cities computed itself
     * @param nativeAnswer                   {@code resolve(...)} with that TLC neighbour value
     * @param managedAnswer                   {@code resolve(...)} with the Citylines double-sided mask
     */
    /**
     * Centre class plus the four logical edge classes, e.g. {@code P.P.P}.
     *
     * <p>Computed here rather than on {@code V3CellInfo} on purpose: that type is part of the
     * frozen V3 algorithm, and the version salt forbids touching it. Diagnostics are allowed to
     * format; the algorithm is not allowed to grow convenience accessors for them.
     */
    private static String signature(V3CellInfo cell) {
        if (cell == null) {
            return "<none>";
        }
        StringBuilder sb = new StringBuilder(5);
        sb.append(letter(cell.roadType()));
        for (Direction direction : Direction.VALUES) {
            sb.append(letter(cell.edge(direction)));
        }
        return sb.toString();
    }

    private static char letter(com.scarasol.citylines.road.core.V3RoadType type) {
        return switch (type) {
            case NONE -> '.';
            case TERTIARY -> 't';
            case SECONDARY -> 's';
            case PRIMARY -> 'P';
        };
    }

    public static void recordEffective(CitylinesRoadState state, ChunkCoord coord, V3CellInfo cell,
                                       PlannedRoadType rawRoadType, boolean hasPlannedEdge,
                                       boolean nativeHasConnectedCityNeighbor, PlannedRoadType nativeAnswer,
                                       PlannedRoadType managedAnswer) {
        if (rawRoadType == PlannedRoadType.NONE || !CitylinesConfig.INSTANCE.debugLogging()) {
            return;
        }
        Counters c = counters(state.dimensionId());
        long chunkKey = chunkKey(coord.chunkX(), coord.chunkZ());
        String chunk = "(" + coord.chunkX() + "," + coord.chunkZ() + ")";
        int total = c.roadChunks.incrementAndGet();
        if (!hasPlannedEdge) {
            if (c.strippedByMask.getAndIncrement() < MAX_SAMPLE_CHUNKS) {
                CitylinesMod.LOGGER.info("[citylines] Citylines road stripped by mask rule: dim={} chunk={} raw={} signature={}",
                        state.dimensionId(), chunk, rawRoadType, signature(cell));
            }
        } else if (managedAnswer == PlannedRoadType.NONE) {
            if (c.strippedByVeto.getAndIncrement() < MAX_SAMPLE_CHUNKS) {
                CitylinesMod.LOGGER.info("[citylines] Citylines road stripped by a Lost Cities veto: dim={} chunk={} raw={} signature={}",
                        state.dimensionId(), chunk, rawRoadType, signature(cell));
            }
        } else {
            AtomicInteger kept = managedAnswer == PlannedRoadType.PRIMARY ? c.keptPrimary : c.keptSecondary;
            kept.incrementAndGet();
            Set<Long> sampled = managedAnswer == PlannedRoadType.PRIMARY ? c.primarySamples : c.secondarySamples;
            if (sampled.size() < MAX_SAMPLE_CHUNKS && sampled.add(chunkKey)) {
                CitylinesMod.LOGGER.info("[citylines] Citylines road kept: dim={} chunk={} class={} signature={}",
                        state.dimensionId(), chunk, managedAnswer, signature(cell));
            }
        }
        if (nativeAnswer != managedAnswer) {
            c.differsFromNative.incrementAndGet();
            if (c.differenceSamples.size() < MAX_SAMPLE_CHUNKS && c.differenceSamples.add(chunkKey)) {
                CitylinesMod.LOGGER.info("[citylines] Citylines rule differs from TLC: dim={} chunk={} raw={} signature={} "
                                + "native={} managed={} nativeConnectedCityNeighbour={} plannedEdge={}",
                        state.dimensionId(), chunk, rawRoadType, signature(cell), nativeAnswer, managedAnswer,
                        nativeHasConnectedCityNeighbor, hasPlannedEdge);
            }
        }
        if (total % SUMMARY_EVERY == 0) {
            logSummary(state.dimensionId(), c);
        }
    }

    /** An automatic multi-building section was accepted by Lost Cities in a Citylines dimension. */
    public static void recordMultiSectionSeen(CitylinesRoadState state) {
        if (!CitylinesConfig.INSTANCE.debugLogging()) {
            return;
        }
        counters(state.dimensionId()).multiSections.incrementAndGet();
    }

    /** A multi-building candidate was refused by {@code MultiChunk.canPlaceBuilding} before placement. */
    public static void recordMultiCandidateRefused(CitylinesRoadState state, ChunkCoord candidateTopLeft,
                                                   int dimX, int dimZ) {
        if (!CitylinesConfig.INSTANCE.debugLogging()) {
            return;
        }
        Counters c = counters(state.dimensionId());
        int n = c.multiCandidatesRefused.incrementAndGet();
        if (n <= MAX_SAMPLE_CHUNKS) {
            CitylinesMod.LOGGER.info("[citylines] Citylines multi-building candidate refused: dim={} footprint=({},{}) {}x{}",
                    state.dimensionId(), candidateTopLeft.chunkX(), candidateTopLeft.chunkZ(), dimX, dimZ);
        }
    }

    /** A multi-building section was refused because its footprint covers a Citylines road. */
    public static void recordMultiRefusal(CitylinesRoadState state, ChunkCoord coord, ChunkCoord topLeft,
                                          int dimX, int dimZ) {
        if (!CitylinesConfig.INSTANCE.debugLogging()) {
            return;
        }
        Counters c = counters(state.dimensionId());
        int n = c.multiRefusals.incrementAndGet();
        if (n <= MAX_SAMPLE_CHUNKS) {
            CitylinesMod.LOGGER.info("[citylines] Citylines multi-building refused: dim={} chunk=({},{}) footprint=({},{}) {}x{}",
                    state.dimensionId(), coord.chunkX(), coord.chunkZ(), topLeft.chunkX(), topLeft.chunkZ(), dimX, dimZ);
        }
    }

    private static void logSummary(String dimensionId, Counters c) {
        CitylinesMod.LOGGER.info("[citylines] Citylines road rights so far: dim={} keptPrimary={} keptSecondary={} "
                        + "strippedByMask={} strippedByVeto={} differsFromNative={} multiSections={} "
                        + "multiCandidatesRefused={} multiRefusals={}",
                dimensionId, c.keptPrimary.get(), c.keptSecondary.get(), c.strippedByMask.get(),
                c.strippedByVeto.get(), c.differsFromNative.get(), c.multiSections.get(),
                c.multiCandidatesRefused.get(), c.multiRefusals.get());
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static final class Counters {
        final AtomicInteger roadChunks = new AtomicInteger();
        final AtomicInteger keptPrimary = new AtomicInteger();
        final AtomicInteger keptSecondary = new AtomicInteger();
        final AtomicInteger strippedByMask = new AtomicInteger();
        final AtomicInteger strippedByVeto = new AtomicInteger();
        final AtomicInteger differsFromNative = new AtomicInteger();
        final AtomicInteger multiSections = new AtomicInteger();
        final AtomicInteger multiCandidatesRefused = new AtomicInteger();
        final AtomicInteger multiRefusals = new AtomicInteger();
        final Set<Long> primarySamples = ConcurrentHashMap.newKeySet();
        final Set<Long> secondarySamples = ConcurrentHashMap.newKeySet();
        final Set<Long> differenceSamples = ConcurrentHashMap.newKeySet();
    }
}
