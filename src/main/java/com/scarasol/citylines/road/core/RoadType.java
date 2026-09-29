package com.scarasol.citylines.road.core;

/** Road surface hierarchy and packed-key encoding, from no road to arterial. */
public enum RoadType {
    NONE,
    /** Walk 4 + kerb 1 + carriageway 6 + kerb 1 + walk 4. */
    TERTIARY,
    /** Walk 2 + kerb 1 + carriageway 10 + kerb 1 + walk 2. */
    SECONDARY,
    /** Walk 1 + kerb 1 + carriageway 12 + kerb 1 + walk 1. */
    PRIMARY;

    public boolean isRoad() {
        return this != NONE;
    }

    /** Position in the hierarchy {@code NONE < TERTIARY < SECONDARY < PRIMARY}. */
    public int rank() {
        return ordinal();
    }

    /** Lower of two classes by <b>hierarchy</b>; used for shared edge classes. */
    public static RoadType min(RoadType a, RoadType b) {
        return a.rank() <= b.rank() ? a : b;
    }

    /** Higher of two classes by <b>hierarchy</b>. */
    public static RoadType max(RoadType a, RoadType b) {
        return a.rank() >= b.rank() ? a : b;
    }

    public static RoadType byOrdinalSafe(int ordinal) {
        RoadType[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
    }

    /**
     * Every class from {@code NONE} up to and including this one, in hierarchy order.
     *
     * <p>This is the alphabet of edge classes a centre of this class can share, because a
     * shared edge is {@code min(centre, neighbour)} and can therefore never exceed the
     * centre.
     */
    public RoadType[] upTo() {
        RoadType[] result = new RoadType[rank() + 1];
        for (RoadType candidate : values()) {
            if (candidate.rank() <= rank()) {
                result[candidate.rank()] = candidate;
            }
        }
        for (int i = 0; i < result.length; i++) {
            if (result[i] == null) {
                throw new IllegalStateException("hierarchy ranks are not contiguous at " + i);
            }
        }
        return result;
    }
}
