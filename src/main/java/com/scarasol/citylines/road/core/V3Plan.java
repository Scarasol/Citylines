package com.scarasol.citylines.road.core;

import java.util.Arrays;
import java.util.List;

/** Published, immutable graph of one S x S supercell; border masks include agreed outer edges. */
public final class V3Plan {
    /** Area counts describe the buildable regions before local roads, not final graph faces. */
    public record Stats(int factSamples, int detours, int mergedSegments, int preLocalClosedAreas,
                        int preLocalOpenAreas, int minPreLocalClosedArea, int maxPreLocalClosedArea,
                        int localThrough, int localSpurs, int stationWins,
                        int lostParcelAreas, int unrepairedAxisGaps, int budgetExhausted,
                        int droppedIsolated, int preRescueNoLocalRouteAreas,
                        int rareRescues, int rareSuppressedLocals, int rareSuppressedCells,
                        int rareNoRawCandidate, int rareAxisBlocked, int rareSpineBlocked,
                        int localCrossFace, int localBent, int localTemplateChecks,
                        int localResidualChecks, int crossTwoAreas, int crossThreeAreas,
                        int crossDeepTwo, int crossDeepThree, int crossMinOne, int crossMinTwo,
                        int crossMinThree, int crossMinFourPlus, int crossScoreTies) { }

    private static final V3RoadType[] TYPES = V3RoadType.values();
    private static final V3EndReason[] REASONS = V3EndReason.values();

    private final DistrictKey key;
    private final int size;
    private final byte[] types;
    private final byte[] masks;
    private final byte[] edges;
    private final byte[] reasons;
    private final boolean[] stationWins;
    private final byte[] transitionMasks;
    private final List<V3AxisGap> axisGaps;
    private final Stats stats;
    private final long fingerprint;
    private final long digest;

    V3Plan(DistrictKey key, int size, byte[] types, byte[] masks, byte[] edges, byte[] reasons,
           boolean[] stationWins, List<V3AxisGap> axisGaps, Stats stats, long fingerprint) {
        int cells = size * size;
        if (types.length != cells || masks.length != cells || edges.length != cells * Direction.COUNT
                || reasons.length != cells
                || stationWins.length != cells) {
            throw new IllegalArgumentException("V3 plan arrays have inconsistent sizes");
        }
        this.key = key;
        this.size = size;
        this.types = types.clone();
        this.masks = masks.clone();
        this.edges = edges.clone();
        this.reasons = reasons.clone();
        this.stationWins = stationWins.clone();
        this.transitionMasks = new byte[cells];
        for (int at = 0; at < cells; at++) {
            if (types[at] == 0) continue;
            int x = at % size;
            int z = at / size;
            for (Direction direction : Direction.VALUES) {
                if ((masks[at] & direction.bit()) == 0) continue;
                int nx = x + direction.dx();
                int nz = z + direction.dz();
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                int other = types[nz * size + nx] & 0xff;
                if ((types[at] == V3RoadType.TERTIARY.ordinal() && other > V3RoadType.TERTIARY.ordinal())
                        || (types[at] > V3RoadType.TERTIARY.ordinal()
                        && other == V3RoadType.TERTIARY.ordinal())) {
                    transitionMasks[at] |= (byte) direction.bit();
                }
            }
        }
        this.axisGaps = List.copyOf(axisGaps);
        this.stats = stats;
        this.fingerprint = fingerprint;
        long hash = Hash.combine(fingerprint, Hash.combine(key.dx(), key.dz()));
        for (int i = 0; i < cells; i++) {
            hash = Hash.combine(hash, (types[i] & 0xff) | ((masks[i] & 0xf) << 3)
                    | ((reasons[i] & 0xff) << 7) | (stationWins[i] ? 1 << 12 : 0));
            for (Direction direction : Direction.VALUES) {
                hash = Hash.combine(hash, edges[i * Direction.COUNT + direction.ordinal()]);
            }
        }
        for (V3AxisGap gap : axisGaps) {
            hash = Hash.combine(hash, Hash.combine(gap.startX(), gap.startZ()));
            hash = Hash.combine(hash, Hash.combine(gap.endX(), gap.endZ()));
            hash = Hash.combine(hash, gap.grade().ordinal() | (gap.reason().ordinal() << 3)
                    | (gap.internal() ? 1 << 10 : 0)
                    | (gap.connectedAround() ? 1 << 11 : 0));
        }
        this.digest = hash;
    }

    public DistrictKey key() {
        return key;
    }

    public int size() {
        return size;
    }

    public Stats stats() {
        return stats;
    }

    public List<V3AxisGap> axisGaps() {
        return axisGaps;
    }

    public long fingerprint() {
        return fingerprint;
    }

    public long digest() {
        return digest;
    }

    public boolean owns(int x, int z) {
        return Math.floorDiv(x, size) == key.dx() && Math.floorDiv(z, size) == key.dz();
    }

    public V3RoadType roadTypeAt(int x, int z) {
        return TYPES[types[index(x, z)] & 0xff];
    }

    public int edgeMaskAt(int x, int z) {
        return masks[index(x, z)] & 0xf;
    }

    public V3EndReason endReasonAt(int x, int z) {
        return REASONS[reasons[index(x, z)] & 0xff];
    }

    public boolean roadWinsStationAt(int x, int z) {
        return stationWins[index(x, z)];
    }

    public int transitionMaskAt(int x, int z) {
        return transitionMasks[index(x, z)] & 0xf;
    }

    public V3CellInfo infoAt(int x, int z) {
        int index = index(x, z);
        V3RoadType type = TYPES[types[index] & 0xff];
        int offset = index * Direction.COUNT;
        return new V3CellInfo(type, TYPES[edges[offset] & 0xff], TYPES[edges[offset + 1] & 0xff],
                TYPES[edges[offset + 2] & 0xff], TYPES[edges[offset + 3] & 0xff],
                stationWins[index], REASONS[reasons[index] & 0xff], transitionMasks[index] & 0xf);
    }

    public int roadCount() {
        int count = 0;
        for (byte type : types) {
            if (type != 0) count++;
        }
        return count;
    }

    public int count(V3RoadType type) {
        int count = 0;
        for (byte candidate : types) {
            if (candidate == type.ordinal()) count++;
        }
        return count;
    }

    public byte[] copyTypes() {
        return Arrays.copyOf(types, types.length);
    }

    private int index(int x, int z) {
        if (!owns(x, z)) {
            throw new IllegalArgumentException("chunk " + x + "," + z + " is not owned by " + key);
        }
        return Math.floorMod(z, size) * size + Math.floorMod(x, size);
    }
}
