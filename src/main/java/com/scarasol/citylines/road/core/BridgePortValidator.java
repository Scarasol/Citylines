package com.scarasol.citylines.road.core;

/**
 * Pure validation hook for the rendered port at a bridge shore.
 *
 * <p>The abstract planner does not know Minecraft asset tables. The TLC adapter
 * supplies a deterministic validator that adds one PRIMARY bridge arm to the shore
 * cell and checks that the existing part table has a matching piece. A missing port
 * rejects the complete span, never just one shore.
 */
@FunctionalInterface
public interface BridgePortValidator {

    boolean canAttach(V3CellInfo shore, Axis axis, Direction outward);

    static BridgePortValidator acceptAll() {
        return (shore, axis, outward) -> true;
    }
}
