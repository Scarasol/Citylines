package com.scarasol.citylines.road.core;

/** A one-edge road cell is never left without an explicit planning reason. */
public enum V3EndReason {
    NONE,
    LOCAL_ACCESS,
    CITY_EDGE,
    HARD_OBSTACLE,
    LEVEL_CHANGE,
    WATER,
    STATION,
    PREDEFINED,
    REPAIR_BUDGET,
    UNROUTABLE
}
