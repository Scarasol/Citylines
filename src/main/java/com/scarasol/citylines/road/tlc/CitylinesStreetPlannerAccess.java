package com.scarasol.citylines.road.tlc;

/**
 * Duck interface added to Lost Cities' {@code HierarchicalStreetPlanner} by mixin.
 *
 * <p>The TLC planner is {@code final} and knows nothing about a dimension, so the
 * per-dimension Citylines state is attached to the planner instance that the dimension
 * created. This is a compile-time interface added by Mixin — no reflection, no
 * static registry that could collide between worlds sharing a seed.
 */
public interface CitylinesStreetPlannerAccess {

    void citylines$setRoadState(CitylinesRoadState state);

    CitylinesRoadState citylines$roadState();
}
