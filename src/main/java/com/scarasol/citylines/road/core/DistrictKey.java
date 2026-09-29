package com.scarasol.citylines.road.core;

/**
 * Identity of one planning district: an {@code S x S} block of chunks.
 *
 * <p>Districts tile the world with {@code floorDiv}, so negative coordinates are
 * handled consistently: chunk {@code x} belongs to district
 * {@code floorDiv(x, S)} at local index {@code floorMod(x, S)}.
 */
public record DistrictKey(int dx, int dz) {

    public static DistrictKey of(int chunkX, int chunkZ, int size) {
        return new DistrictKey(Math.floorDiv(chunkX, size), Math.floorDiv(chunkZ, size));
    }

    public static int localOf(int chunkCoordinate, int size) {
        return Math.floorMod(chunkCoordinate, size);
    }

    public int originX(int size) {
        return dx * size;
    }

    public int originZ(int size) {
        return dz * size;
    }

    public int index(int localX, int localZ, int size) {
        return localZ * size + localX;
    }

    @Override
    public String toString() {
        return "D[" + dx + "," + dz + "]";
    }

    /**
     * Canonical identity of the shared border between two adjacent districts.
     *
     * <p>Both sides of a border derive the same object, so both compute the same
     * gate without knowing anything about the other side's traversal order.
     *
     * @param zAxis true when the border separates districts along Z (a horizontal
     *              border), false when it separates them along X
     * @param dx    x index of the district with the lower coordinate on the split axis
     * @param dz    z index of that district
     */
    public record BorderKey(boolean zAxis, int dx, int dz) {

        public static BorderKey between(DistrictKey a, DistrictKey b) {
            if (a.dz == b.dz && Math.abs(a.dx - b.dx) == 1) {
                return new BorderKey(false, Math.min(a.dx, b.dx), a.dz);
            }
            if (a.dx == b.dx && Math.abs(a.dz - b.dz) == 1) {
                return new BorderKey(true, a.dx, Math.min(a.dz, b.dz));
            }
            throw new IllegalArgumentException("districts are not adjacent: " + a + " / " + b);
        }

        /** District with the lower coordinate on the split axis. */
        public DistrictKey low() {
            return new DistrictKey(dx, dz);
        }

        /** District with the higher coordinate on the split axis. */
        public DistrictKey high() {
            return zAxis ? new DistrictKey(dx, dz + 1) : new DistrictKey(dx + 1, dz);
        }

        @Override
        public String toString() {
            return (zAxis ? "H" : "V") + "[" + dx + "," + dz + "]";
        }
    }
}
