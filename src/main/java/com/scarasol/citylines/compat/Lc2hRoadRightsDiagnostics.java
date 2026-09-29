package com.scarasol.citylines.compat;

import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot logging for the current LC2H occupancy adapter. */
public final class Lc2hRoadRightsDiagnostics {

    private static final AtomicBoolean MERGED_ONCE = new AtomicBoolean();

    private Lc2hRoadRightsDiagnostics() {
    }

    /** @return true the first time a Citylines dimension contributes road occupancy to the fast multi-chunk plan. */
    public static boolean firstMerge() {
        return MERGED_ONCE.compareAndSet(false, true);
    }
}
