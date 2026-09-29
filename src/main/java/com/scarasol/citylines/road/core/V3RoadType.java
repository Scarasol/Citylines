package com.scarasol.citylines.road.core;

/** Logical road hierarchy of the V3 graph. Rendering may use a different port profile. */
public enum V3RoadType {
    NONE,
    TERTIARY,
    SECONDARY,
    PRIMARY;

    public boolean isRoad() {
        return this != NONE;
    }

    public static V3RoadType shared(V3RoadType a, V3RoadType b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
