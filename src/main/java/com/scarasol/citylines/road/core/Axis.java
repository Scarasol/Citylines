package com.scarasol.citylines.road.core;

/** Axis a bridge span or a corridor band runs along. */
public enum Axis {
    X,
    Z;

    public Axis other() {
        return this == X ? Z : X;
    }

    /** Coordinate of a cell along this axis. */
    public int along(Cell cell) {
        return this == X ? cell.x() : cell.z();
    }

    /** Coordinate of a cell perpendicular to this axis. */
    public int across(Cell cell) {
        return this == X ? cell.z() : cell.x();
    }

    public Cell cell(int along, int across) {
        return this == X ? new Cell(along, across) : new Cell(across, along);
    }
}
