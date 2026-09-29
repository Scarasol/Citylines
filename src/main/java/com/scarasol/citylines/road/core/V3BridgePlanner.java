package com.scarasol.citylines.road.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic overlay for short PRIMARY roads crossing open water.
 *
 * <p>Land V3 is never modified. A candidate is the <b>maximum contiguous</b> water
 * run immediately after a PRIMARY shore and before the first non-water cell. Both
 * shores must already have an inland PRIMARY edge. The candidate is then checked by
 * one symmetric predicate and only the complete span is published.
 *
 * <p>Queries are local and bounded. The cache stores both positive and negative
 * answers, so repeated TLC consumers do not rescan the same water gap. Conflict
 * resolution is performed over complete spans: the shorter water run wins, then the
 * axis and absolute coordinates provide a stable tie-break. A losing span is absent
 * everywhere, including its non-overlapping tail.
 */
public final class V3BridgePlanner {

    private static final int DEFAULT_CACHE_CAPACITY = 4096;
    private static final Comparator<BridgeSpan> PRIORITY = Comparator
            .comparingInt(BridgeSpan::waterCells)
            .thenComparingInt(span -> span.axis().ordinal())
            .thenComparingInt(BridgeSpan::fixed)
            .thenComparingInt(BridgeSpan::from)
            .thenComparingInt(BridgeSpan::to);

    private final V3BridgeParams params;
    private final V3BridgeInput input;
    private final BridgePortValidator ports;
    private final Map<Long, BridgeDecision> positive;
    private final Set<Long> negative;

    public V3BridgePlanner(V3BridgeParams params, V3BridgeInput input,
                           BridgePortValidator ports) {
        this(params, input, ports, DEFAULT_CACHE_CAPACITY);
    }

    public V3BridgePlanner(V3BridgeParams params, V3BridgeInput input,
                           BridgePortValidator ports, int cacheCapacity) {
        if (params == null || input == null || ports == null || cacheCapacity < 1) {
            throw new IllegalArgumentException("bridge planner needs inputs and positive cache capacity");
        }
        this.params = params;
        this.input = input;
        this.ports = ports;
        this.positive = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, BridgeDecision> eldest) {
                return size() > V3BridgePlanner.this.cacheCapacity;
            }
        };
        this.negative = new LinkedHashSet<>();
        this.cacheCapacity = cacheCapacity;
    }

    private final int cacheCapacity;

    public V3BridgeParams params() {
        return params;
    }

    /**
     * Returns the winning complete span touching this cell, or {@code null}.
     * Water cells are deck cells; shore cells are returned so TLC can add the same
     * bridge arm to both land endpoints.
     */
    public synchronized BridgeDecision decisionAt(int chunkX, int chunkZ) {
        long key = coordinateKey(chunkX, chunkZ);
        BridgeDecision cached = positive.get(key);
        if (cached != null) {
            return cached;
        }
        if (negative.contains(key)) {
            return null;
        }
        BridgeDecision decision = resolve(chunkX, chunkZ);
        if (decision == null) {
            negative.add(key);
            trimNegative();
        } else {
            positive.put(key, decision);
        }
        return decision;
    }

    public synchronized int cachedAnswers() {
        return positive.size() + negative.size();
    }

    /** Candidate predicate exposed for abstract contract tests and TLC adapters. */
    public boolean validCandidate(BridgeSpan span) {
        return span != null && span.waterCells() <= params.maxWaterCells()
                && candidatePredicate(span);
    }

    private BridgeDecision resolve(int chunkX, int chunkZ) {
        List<BridgeSpan> initial = candidatesTouching(chunkX, chunkZ);
        if (initial.isEmpty()) {
            return null;
        }

        // Find every candidate that can overlap an initial span. The search is finite
        // because every span is at most maxWaterCells + 1 cells long. Cross-axis
        // candidates are included so an X and Z bridge cannot claim the same chunk.
        Set<BridgeSpan> all = new LinkedHashSet<>(initial);
        for (BridgeSpan span : initial) {
            collectConflicts(span, all);
        }

        List<BridgeSpan> ordered = new ArrayList<>(all);
        ordered.sort(PRIORITY);
        List<BridgeSpan> winners = new ArrayList<>();
        for (BridgeSpan candidate : ordered) {
            boolean overlapsWinner = false;
            for (BridgeSpan winner : winners) {
                if (intersects(candidate, winner)) {
                    overlapsWinner = true;
                    break;
                }
            }
            if (!overlapsWinner) {
                winners.add(candidate);
            }
        }
        Cell query = new Cell(chunkX, chunkZ);
        for (BridgeSpan winner : winners) {
            if (winner.touches(query)) {
                return new BridgeDecision(winner, params.spanId(winner));
            }
        }
        return null;
    }

    private List<BridgeSpan> candidatesTouching(int chunkX, int chunkZ) {
        int maxSpan = params.maxWaterCells() + 1;
        Set<BridgeSpan> candidates = new LinkedHashSet<>();
        for (int start = chunkX - maxSpan; start <= chunkX; start++) {
            addCandidate(candidates, Axis.X, chunkZ, start);
        }
        for (int start = chunkZ - maxSpan; start <= chunkZ; start++) {
            addCandidate(candidates, Axis.Z, chunkX, start);
        }
        return List.copyOf(candidates);
    }

    private void collectConflicts(BridgeSpan span, Set<BridgeSpan> out) {
        int maxSpan = params.maxWaterCells() + 1;
        if (span.axis() == Axis.X) {
            for (int start = span.from() - maxSpan; start <= span.to(); start++) {
                addCandidate(out, Axis.X, span.fixed(), start);
            }
            for (int fixed = span.from(); fixed <= span.to(); fixed++) {
                for (int start = span.fixed() - maxSpan; start <= span.fixed(); start++) {
                    addCandidate(out, Axis.Z, fixed, start);
                }
            }
        } else {
            for (int start = span.from() - maxSpan; start <= span.to(); start++) {
                addCandidate(out, Axis.Z, span.fixed(), start);
            }
            for (int fixed = span.from(); fixed <= span.to(); fixed++) {
                for (int start = span.fixed() - maxSpan; start <= span.fixed(); start++) {
                    addCandidate(out, Axis.X, fixed, start);
                }
            }
        }
    }

    private void addCandidate(Set<BridgeSpan> out, Axis axis, int fixed, int start) {
        BridgeSpan candidate = candidateFromLowShore(axis, fixed, start);
        if (candidate != null) {
            out.add(candidate);
        }
    }

    private BridgeSpan candidateFromLowShore(Axis axis, int fixed, int lowAlong) {
        Cell lowCell = axis.cell(lowAlong, fixed);
        ChunkFacts lowFacts = input.factsAt(lowCell.x(), lowCell.z());
        if (!isShore(lowFacts) || !hasInlandPrimary(axis, lowCell, false)) {
            return null;
        }

        int water = 0;
        while (water < params.maxWaterCells()) {
            Cell probe = axis.cell(lowAlong + water + 1, fixed);
            ChunkFacts facts = input.factsAt(probe.x(), probe.z());
            if (facts == null || facts.cityRaw() || !facts.bridgeWater() || facts.highwayConflict()) {
                break;
            }
            water++;
        }
        if (water < 1) {
            return null;
        }

        Cell highCell = axis.cell(lowAlong + water + 1, fixed);
        ChunkFacts highFacts = input.factsAt(highCell.x(), highCell.z());
        if (!isShore(highFacts) || !hasInlandPrimary(axis, highCell, true)) {
            return null;
        }
        BridgeSpan span = new BridgeSpan(axis, fixed, lowAlong, lowAlong + water + 1);
        return candidatePredicate(span) ? span : null;
    }

    private boolean candidatePredicate(BridgeSpan span) {
        Cell low = span.lowShore();
        Cell high = span.highShore();
        ChunkFacts lowFacts = input.factsAt(low.x(), low.z());
        ChunkFacts highFacts = input.factsAt(high.x(), high.z());
        if (!isShore(lowFacts) || !isShore(highFacts) || !lowFacts.sameLevel(highFacts)) {
            return false;
        }
        if (!ports.canAttach(input.roadAt(low.x(), low.z()), span.axis(), outward(span.axis(), false))
                || !ports.canAttach(input.roadAt(high.x(), high.z()), span.axis(), outward(span.axis(), true))) {
            return false;
        }
        for (int along = span.from() + 1; along < span.to(); along++) {
            Cell water = span.axis().cell(along, span.fixed());
            ChunkFacts facts = input.factsAt(water.x(), water.z());
            if (facts == null || facts.cityRaw() || !facts.bridgeWater() || facts.highwayConflict()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasInlandPrimary(Axis axis, Cell shore, boolean highSide) {
        V3CellInfo cell = input.roadAt(shore.x(), shore.z());
        if (cell == null || cell.roadType() != V3RoadType.PRIMARY) {
            return false;
        }
        Direction inland = highSide ? outward(axis, false) : outward(axis, true);
        return cell.edge(inland) == V3RoadType.PRIMARY;
    }

    private static Direction outward(Axis axis, boolean highSide) {
        if (axis == Axis.X) {
            return highSide ? Direction.W : Direction.E;
        }
        return highSide ? Direction.N : Direction.S;
    }

    private static boolean isShore(ChunkFacts facts) {
        return facts != null && facts.bridgeShore();
    }

    /** Two spans conflict when they share a deck or shore chunk. */
    static boolean intersects(BridgeSpan first, BridgeSpan second) {
        if (first.axis() == second.axis()) {
            return first.fixed() == second.fixed()
                    && first.from() <= second.to() && second.from() <= first.to();
        }
        BridgeSpan x = first.axis() == Axis.X ? first : second;
        BridgeSpan z = first.axis() == Axis.Z ? first : second;
        return z.fixed() >= x.from() && z.fixed() <= x.to()
                && x.fixed() >= z.from() && x.fixed() <= z.to();
    }

    private static long coordinateKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private void trimNegative() {
        while (negative.size() > cacheCapacity) {
            negative.remove(negative.iterator().next());
        }
    }
}
