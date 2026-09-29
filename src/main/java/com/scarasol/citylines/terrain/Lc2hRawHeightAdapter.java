package com.scarasol.citylines.terrain;

/**
 * Marker implemented on {@code mcjty.lostcities.worldgen.lost.City} by the optional LC2H adapter mixin.
 *
 * <p>When LC2H is present it replaces {@code City.getCityFactor} with its own fast path and moves the
 * city-height gates into {@code lc2h$finishNormalCityFactor} / {@code lc2h$getCityFactorUncached},
 * where they may read LC2H's own natural-height cache instead of Lost Cities' heightmap. The uniform
 * plane is only allowed to activate when that rewrite was actually adapted, because otherwise the city
 * domain would depend on cache readiness instead of being a pure function of the seed (plan §4.4).
 *
 * <p>The marker is a compile-time interface (no reflection): the adapter mixin adds it to {@code City},
 * so {@code Lc2hRawHeightAdapter.class.isAssignableFrom(City.class)} answers "did the adapter apply".
 */
public interface Lc2hRawHeightAdapter {
}
