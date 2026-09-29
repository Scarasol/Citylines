package com.scarasol.citylines.road.core;

/** Parameters selected on abstract maps, independent of Minecraft world seeds. */
public record V3Params(int areaSize, int supercellFactor, int minCollectors, int maxCollectors,
                       int minAxisGap, double mergeChance, int spurDepth, int throughDepth,
                       int maxSpurLength, int detourRadius, int maxDetours, int localCandidates,
                       int localsPerFace, int extraLocalDepth, int algorithmVersion) {
    public static final int CURRENT_VERSION = 7;

    /**
     * The reviewed defaults.
     *
     * <p>{@code localsPerFace = 2} is decision D10/D13: after the first local road of a block the
     * remaining cells are re-partitioned and a still-deep sub-face may take one more local road.
     * The design allows 2..3, and {@code extraLocalDepth} is the depth a sub-face must still reach
     * for that second road; it is deliberately stricter than the first-round thresholds so the
     * density rises moderately instead of doubling. Both values were chosen from the measured
     * abstract density (see {@code docs/V3-四问题改动交付.md} §7).
     */
    public static V3Params selectedForDefaultArea() {
        return new V3Params(10, 3, 1, 1, 6, 0.7, 3, 6, 4, 2, 6, 5, 2, 8,
                CURRENT_VERSION);
    }

    public V3Params {
        if (areaSize < 4 || areaSize > 32 || supercellFactor < 2 || supercellFactor > 5) {
            throw new IllegalArgumentException("invalid V3 planning scale");
        }
        int size = areaSize * supercellFactor;
        if (size > 96 || minCollectors < 1 || maxCollectors < minCollectors || maxCollectors > 3
                || (maxCollectors + 1) * minAxisGap > size || minAxisGap < 4) {
            throw new IllegalArgumentException("collector axes do not fit the supercell");
        }
        if (mergeChance < 0 || mergeChance > 1 || Double.isNaN(mergeChance)
                || spurDepth < 2 || throughDepth <= spurDepth || maxSpurLength < 2
                || detourRadius < 0 || detourRadius > 4 || maxDetours < 0 || maxDetours > 16
                || localCandidates < 1 || localCandidates > 16
                || localsPerFace < 1 || localsPerFace > 3 || extraLocalDepth < spurDepth
                || algorithmVersion < 1) {
            throw new IllegalArgumentException("invalid V3 routing parameters");
        }
    }

    public int supercellSize() {
        return areaSize * supercellFactor;
    }

    public long fingerprint(long seed, String dimensionId) {
        long hash = Hash.dimension(seed, dimensionId);
        for (long value : new long[] {areaSize, supercellFactor, minCollectors, maxCollectors,
                minAxisGap, Double.doubleToLongBits(mergeChance), spurDepth, throughDepth,
                maxSpurLength, detourRadius, maxDetours, localCandidates, localsPerFace,
                extraLocalDepth, algorithmVersion}) {
            hash = Hash.combine(hash, value);
        }
        return hash;
    }
}
