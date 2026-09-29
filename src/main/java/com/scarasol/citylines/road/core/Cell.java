package com.scarasol.citylines.road.core;

/**
 * An immutable chunk coordinate in chunk space.
 *
 * <p>All arithmetic that maps a global chunk coordinate to a district or a local
 * index goes through {@link #floorDiv}/{@link #floorMod} so that negative
 * coordinates behave the same as positive ones. Getting this wrong is the classic
 * source of "the plan differs when the player approaches from the other side".
 */
public record Cell(int x, int z) {

    public Cell neighbor(Direction direction) {
        return new Cell(x + direction.dx(), z + direction.dz());
    }

    public Cell offset(int dx, int dz) {
        return new Cell(x + dx, z + dz);
    }

    /** Canonical ordering: z first, then x. Used for deterministic tie-breaks. */
    public int compareCanonical(Cell other) {
        int c = Integer.compare(z, other.z);
        return c != 0 ? c : Integer.compare(x, other.x);
    }

    public long packed() {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return "(" + x + "," + z + ")";
    }
}
