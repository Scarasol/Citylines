package com.scarasol.citylines.road.core;

/** One immutable V3 graph query. Edge classes are logical, not rendering aliases. */
public record V3CellInfo(V3RoadType roadType, V3RoadType north, V3RoadType east,
                         V3RoadType south, V3RoadType west, boolean roadWinsStation,
                         V3EndReason endReason, int transitionMask) {
    public V3RoadType edge(Direction direction) {
        return switch (direction) {
            case N -> north;
            case E -> east;
            case S -> south;
            case W -> west;
        };
    }

    public int edgeMask() {
        int mask = 0;
        for (Direction direction : Direction.VALUES) {
            if (edge(direction).isRoad()) {
                mask |= direction.bit();
            }
        }
        return mask;
    }

    /** A high/local boundary uses a medium-width render port without changing its logical edge. */
    public boolean transitionArm(Direction direction) {
        return (transitionMask & direction.bit()) != 0;
    }
}
