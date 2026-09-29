package com.scarasol.citylines.road.core;

/**
 * Horizontal directions in chunk space.
 *
 * <p>Index order is the ABI of every mask and port signature in this package:
 * {@code N=0, E=1, S=2, W=3}. Never reorder; persisted plans and asset selection
 * tables depend on it.
 */
public enum Direction {
    N(0, -1),
    E(1, 0),
    S(0, 1),
    W(-1, 0);

    public static final Direction[] VALUES = values();
    public static final int COUNT = VALUES.length;

    private final int dx;
    private final int dz;

    Direction(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    /** Bit used in a four-direction mask. */
    public int bit() {
        return 1 << ordinal();
    }

    public Direction opposite() {
        return VALUES[(ordinal() + 2) & 3];
    }

    /** Clockwise turn in screen coordinates (N -> E -> S -> W). */
    public Direction clockwise() {
        return VALUES[(ordinal() + 1) & 3];
    }

    public Direction counterClockwise() {
        return VALUES[(ordinal() + 3) & 3];
    }

    public static Direction of(int index) {
        return VALUES[index & 3];
    }

    /** Direction from {@code (x,z)} towards the orthogonally adjacent cell. */
    public static Direction between(int x, int z, int nx, int nz) {
        int dx = Integer.signum(nx - x);
        int dz = Integer.signum(nz - z);
        for (Direction d : VALUES) {
            if (d.dx == dx && d.dz == dz) {
                return d;
            }
        }
        throw new IllegalArgumentException("not orthogonally adjacent: (" + x + "," + z + ") -> (" + nx + "," + nz + ")");
    }

    /** True when the two cells are orthogonally adjacent. */
    public static boolean adjacent(int x, int z, int nx, int nz) {
        return Math.abs(nx - x) + Math.abs(nz - z) == 1;
    }

    public static int mask(Direction... directions) {
        int mask = 0;
        for (Direction d : directions) {
            mask |= d.bit();
        }
        return mask;
    }

    public static int count(int mask) {
        return Integer.bitCount(mask & 0xF);
    }
}
