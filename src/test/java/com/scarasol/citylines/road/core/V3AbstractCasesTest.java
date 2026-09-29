package com.scarasol.citylines.road.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the V3 abstract invariants under JUnit so {@code gradlew test} really covers them.
 *
 * <p>Before this class the only V3 abstract evidence was the hand-run CLI command
 * {@code java ... V3AbstractCases verify}: {@code @Test} count was 0, so nothing in the
 * regression suite protected {@code road/core/V3*}. Each test below drives one
 * {@code V3AbstractCases.verify*} group, which is also what {@code verify} (the CLI form)
 * calls in order -- so the two entry points cannot drift apart.
 *
 * <p>The checks themselves are unchanged from the reviewed CLI validator. They throw
 * {@link AssertionError} on a violated invariant, which JUnit reports as a failure.
 */
class V3AbstractCasesTest {

    private static final V3Params P = V3Params.selectedForDefaultArea();

    @Test
    @DisplayName("default area parameters keep the reviewed scale and version salt")
    void defaultParametersKeepTheReviewedScale() {
        assertDoesNotThrow(V3AbstractCases::verifyParameters);
        assertEquals(10, P.areaSize(), "M must stay 10 while only areasize()==10 has V3 parameters");
        assertEquals(3, P.supercellFactor(), "K=3 is the reviewed prototype value");
        assertEquals(30, P.supercellSize(), "S = M * K");
        assertEquals(V3Params.CURRENT_VERSION, P.algorithmVersion(),
                "any geometry or policy change after the freeze must bump the version salt");
    }

    @Test
    @DisplayName("station entrances on a mandatory axis prefer a bounded detour, else road priority")
    void stationEntrancesPreferDetourThenRoadPriority() {
        V3AbstractCases.verifyStationChoices(P);
    }

    @Test
    @DisplayName("blocked axis gaps record their reason and agree with the final edge graph")
    void axisGapsRecordReasonAndFinalConnectivity() {
        V3AbstractCases.verifyGapReasons(P);
    }

    @Test
    @DisplayName("render transition ports are local, mirrored and never upgrade an edge class")
    void transitionPortsStayLocalAndMirrored() {
        V3AbstractCases.verifyTransitionPorts(P);
    }

    @Test
    @DisplayName("negative coordinates, cross-border edges, end reasons and eviction hold in four worlds")
    void worldInvariantsHoldInEveryFactWorld() {
        V3AbstractCases.verifyWorldInvariants();
    }

    @Test
    @DisplayName("the open map keeps three road classes, no lost parcel area and 3x3/7x7/9x9 candidates")
    void openMapKeepsEveryRoadClassAndLargeParcels() {
        V3AbstractCases.verifyOpenMapContent();
    }

    @Test
    @DisplayName("a feasible 7x7/9x9 area is never lost while primary and secondary roads allow it")
    void conditionalFallbackNeverLosesAFeasibleParcel() {
        V3AbstractCases.verifyFeasibleParcels();
    }

    @Test
    @DisplayName("the conditional fallback fires and counts the local cells it withdraws")
    void conditionalFallbackFiresAndCountsWithdrawnCells() {
        V3AbstractCases.verifyRareRescue();
    }

    @Test
    @DisplayName("the final graph contains bent and cross-face local roads")
    void continuousLocalRoutesExist() {
        V3AbstractCases.verifyContinuousLocals();
    }

    @Test
    @DisplayName("non-nested rare footprints survive, and eviction does not change the rescue")
    void nonNestedRareFootprintsSurviveEviction() {
        V3AbstractCases.verifyNonNestedRareFootprints(P);
    }

    @Test
    @DisplayName("a large-only asset list keeps at least one parcel and still lays local roads")
    void largeOnlyAssetListKeepsAParcelAndLocalRoads() {
        V3AbstractCases.verifyLargeOnlyFootprint();
    }

    /**
     * Documented in the V3 handover: {@code fingerprint()} covers the seed, dimension id,
     * parameter version and the distinct footprint sizes, but <em>not</em> the per-chunk fact
     * content. The integration layer must therefore freeze facts and invalidate its own caches,
     * so this asymmetry is pinned here rather than left implicit.
     */
    @Test
    @DisplayName("fingerprint ignores per-chunk facts but tracks footprints, dimension and version")
    void fingerprintCoversInputsButNotPerChunkFacts() {
        long empty = new V3Planner(7, "abstract", P, (x, z) -> V3Facts.EMPTY, V3AbstractCases.footprints())
                .fingerprint();
        long city = new V3Planner(7, "abstract", P, (x, z) -> V3Facts.city(0), V3AbstractCases.footprints())
                .fingerprint();
        assertEquals(empty, city, "the fingerprint must not depend on the per-chunk fact content");

        long otherSeed = new V3Planner(8, "abstract", P, (x, z) -> V3Facts.city(0),
                V3AbstractCases.footprints()).fingerprint();
        long otherDimension = new V3Planner(7, "other", P, (x, z) -> V3Facts.city(0),
                V3AbstractCases.footprints()).fingerprint();
        long otherFootprints = new V3Planner(7, "abstract", P, (x, z) -> V3Facts.city(0),
                V3AbstractCases.largeFootprints()).fingerprint();
        assertTrue(otherSeed != city, "a different seed must change the fingerprint");
        assertTrue(otherDimension != city, "a different dimension id must change the fingerprint");
        assertTrue(otherFootprints != city, "a different asset footprint list must change the fingerprint");
    }

    /**
     * Documented interface rule: a {@code null} fact is treated as {@link V3Facts#EMPTY}, so a
     * fact source that answers null everywhere plans an empty graph instead of failing.
     */
    @Test
    @DisplayName("a null fact is treated as an empty fact, not as a planning failure")
    void nullFactsAreTreatedAsEmptyFacts() {
        V3Plan plan = new V3Planner(31, "abstract", P, (x, z) -> null, V3AbstractCases.footprints())
                .plan(new DistrictKey(0, 0));
        assertEquals(0, plan.roadCount(), "null facts describe no city, so no road may be planned");
    }
}
