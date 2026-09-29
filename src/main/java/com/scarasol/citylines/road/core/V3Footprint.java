package com.scarasol.citylines.road.core;

/** Actual multi-building footprint size in chunks, supplied by the integration layer. */
public record V3Footprint(int x, int z) {
    public V3Footprint {
        if (x < 1 || z < 1) {
            throw new IllegalArgumentException("footprint dimensions must be positive");
        }
    }

    public int area() {
        return x * z;
    }
}
