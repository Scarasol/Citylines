package com.scarasol.citylines.road.core;

/** A missing segment of a mandatory axis before optional collector merging. */
public record V3AxisGap(int startX, int startZ, int endX, int endZ,
                        V3RoadType grade, V3EndReason reason, boolean internal,
                        boolean connectedAround) { }
