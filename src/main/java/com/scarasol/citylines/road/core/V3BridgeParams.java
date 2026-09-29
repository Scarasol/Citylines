package com.scarasol.citylines.road.core;

/**
 * Versioned parameters for the independent V3 water-bridge overlay.
 *
 * <p>This is deliberately separate from {@link V3Params}: adding a bridge does not
 * change the frozen land-road graph or its algorithm salt. A bridge parameter change
 * changes the effective graph and therefore must change the bridge fingerprint and the
 * persisted world feature version.
 */
public record V3BridgeParams(int algorithmVersion, int maxWaterCells) {

    /** First production bridge contract; the land-road V3 version remains 3. */
    public static final int CURRENT_VERSION = 1;

    /**
     * TLC's existing bounded primary bridge limit, expressed as water cells rather
     * than endpoint distance. The pure bridge cases cover 1..12 and reject 13.
     */
    public static final int DEFAULT_MAX_WATER_CELLS = 12;

    public V3BridgeParams {
        if (algorithmVersion <= 0) {
            throw new IllegalArgumentException("bridge algorithmVersion must be positive");
        }
        if (maxWaterCells < 1 || maxWaterCells > 12) {
            throw new IllegalArgumentException("maxWaterCells must be in 1..12");
        }
    }

    public static V3BridgeParams defaults() {
        return new V3BridgeParams(CURRENT_VERSION, DEFAULT_MAX_WATER_CELLS);
    }

    public long fingerprint(long v3Fingerprint) {
        long hash = Hash.combine(v3Fingerprint, algorithmVersion);
        return Hash.combine(hash, maxWaterCells);
    }

    /** Stable ID for TLC's {@code PlannedBridgeInfo}; includes the bridge version. */
    public long spanId(BridgeSpan span) {
        long hash = Hash.combine(algorithmVersion, span.axis().ordinal());
        hash = Hash.combine(hash, span.fixed());
        hash = Hash.combine(hash, span.from());
        return Hash.combine(hash, span.to());
    }
}
