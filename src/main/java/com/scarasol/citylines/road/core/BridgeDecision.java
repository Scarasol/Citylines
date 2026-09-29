package com.scarasol.citylines.road.core;

/**
 * One accepted, immutable bridge span and the versioned identity used by adapters.
 * A rejected candidate is represented by absence from {@link V3BridgePlanner}; no
 * partial decision is published.
 */
public record BridgeDecision(BridgeSpan span, long id) {

    public BridgeDecision {
        if (span == null) {
            throw new IllegalArgumentException("bridge decision needs a span");
        }
    }

    public int waterCells() {
        return span.waterCells();
    }

    public boolean touches(Cell cell) {
        return span.touches(cell);
    }

    public boolean containsWaterCell(Cell cell) {
        return span.containsWaterCell(cell);
    }
}
