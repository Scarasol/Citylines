package com.scarasol.citylines.road.core;

/** Stable, pre-building-selection facts. Surface rail only vetoes optional seam detours. */
public record V3Facts(boolean city, int cityLevel, boolean land, boolean hardBlocked,
                      boolean stationEntrance, boolean buildingEligible, boolean predefinedBuilding,
                      boolean surfaceRail) {
    public V3Facts(boolean city, int cityLevel, boolean land, boolean hardBlocked,
                   boolean stationEntrance, boolean buildingEligible, boolean predefinedBuilding) {
        this(city, cityLevel, land, hardBlocked, stationEntrance, buildingEligible, predefinedBuilding, false);
    }

    public static final V3Facts EMPTY = new V3Facts(false, 0, false, false, false, false, false);

    public V3Facts(boolean city, int cityLevel, boolean land, boolean hardBlocked,
                   boolean stationEntrance, boolean buildingEligible) {
        this(city, cityLevel, land, hardBlocked, stationEntrance, buildingEligible, false);
    }

    public static V3Facts city(int level) {
        return new V3Facts(true, level, true, false, false, true, false);
    }

    public static V3Facts blocked(int level) {
        return new V3Facts(true, level, true, true, false, false, false);
    }

    public static V3Facts entrance(int level) {
        return new V3Facts(true, level, true, false, true, false, false);
    }

    public static V3Facts predefinedBuilding(int level) {
        return new V3Facts(true, level, true, true, false, false, true);
    }

    public boolean groundPavable() {
        return city && land && !hardBlocked;
    }

    public boolean roadPavable(boolean roadWinsStation) {
        return groundPavable() && (!stationEntrance || roadWinsStation);
    }

    public boolean parcelPavable() {
        return groundPavable() && !stationEntrance && buildingEligible;
    }
}
