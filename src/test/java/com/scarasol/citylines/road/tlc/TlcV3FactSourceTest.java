package com.scarasol.citylines.road.tlc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import mcjty.lostcities.api.RailChunkType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scarasol.citylines.road.core.ChunkFacts;
import com.scarasol.citylines.road.core.DistrictKey;
import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.V3FactSource;
import com.scarasol.citylines.road.core.V3Facts;
import com.scarasol.citylines.road.core.V3Footprint;
import com.scarasol.citylines.road.core.V3Params;
import com.scarasol.citylines.road.core.V3Plan;
import com.scarasol.citylines.road.core.V3Planner;
import com.scarasol.citylines.road.core.V3RoadType;

/**
 * Contract tests for {@link TlcV3FactSource} — step 3 of the V3 integration checklist.
 *
 * <p>These run without a live {@code IDimensionInfo}, so they cover the mapping and the
 * coordinate handling only. They deliberately drive the <b>real</b> TLC composition
 * ({@link TlcRoadFacts#chunkFacts}) instead of hand-built {@code ChunkFacts}, so the
 * rail/obstacle split cannot drift between the two layers.
 */
class TlcV3FactSourceTest {

    /**
     * Builds facts through the <b>real</b> TLC composition, so the rail/obstacle merge under
     * test is the one world generation actually performs. (Using the {@code ChunkFacts.city}
     * convenience factory here would bypass {@link TlcRoadFacts#chunkFacts} and quietly test
     * nothing about the merge.)
     */
    private static ChunkFacts city(int cityLevel, RailChunkType rail, int railHeight) {
        return TlcRoadFacts.chunkFacts(true, cityLevel, true, false, false, false,
                TlcRoadFacts.railBlocksStreet(cityLevel, rail, railHeight), false, false,
                rail, railHeight);
    }

    // ------------------------------------------------------------- mapping -----

    /**
     * TLC's early street predicate and its later railway placement disagree on some
     * surface extensions. The adapter must use the final physical occupancy.
     */
    @Test
    @DisplayName("rail structures generated after the street pass cannot carry V3 pavement")
    void stationEntranceMappingMatchesTheRailRule() {
        // TLC would pave this extension, but its later rail pass clears the surface.
        V3Facts entrance = TlcV3FactSource.toV3Facts(
                city(2, RailChunkType.STATION_EXTENSION_SURFACE, 1));
        assertFalse(entrance.stationEntrance());
        assertTrue(entrance.hardBlocked());
        assertFalse(entrance.roadPavable(true));
        assertFalse(entrance.predefinedBuilding());

        // STATION_SURFACE: TLC never paves it. Hard obstacle, and NOT an entrance.
        V3Facts surface = TlcV3FactSource.toV3Facts(city(2, RailChunkType.STATION_SURFACE, 2));
        assertTrue(surface.hardBlocked(),
                "a surface station is a frozen obstacle: TLC lays no pavement there");
        assertFalse(surface.stationEntrance(),
                "a station with no pavable surface must never be published as a road-adjudicable entrance");
        assertFalse(surface.roadPavable(false), "a hard obstacle is not pavable");
        assertFalse(surface.roadPavable(true),
                "even road-wins-station may not pave a hard obstacle; that would drive a road through a station");

        // Surface extension at city level: blocks the street, so also not an entrance.
        V3Facts blockedExtension = TlcV3FactSource.toV3Facts(
                city(2, RailChunkType.STATION_EXTENSION_SURFACE, 2));
        assertTrue(blockedExtension.hardBlocked(),
                "a surface extension at the city level is where TLC refuses to pave");
        assertFalse(blockedExtension.stationEntrance());
        assertTrue(blockedExtension.surfaceRail());
    }

    @Test
    @DisplayName("underground station stairs occupy the surface, but plain underground rail does not")
    void undergroundStationsAreOrdinaryPavement() {
        V3Facts stairs = TlcV3FactSource.toV3Facts(city(1, RailChunkType.STATION_UNDERGROUND, 0));
        assertTrue(stairs.hardBlocked());
        assertFalse(stairs.predefinedBuilding());
        assertFalse(stairs.roadPavable(false));
        for (RailChunkType type : new RailChunkType[] {
                RailChunkType.STATION_EXTENSION_UNDERGROUND, RailChunkType.NONE,
                RailChunkType.HORIZONTAL}) {
            V3Facts facts = TlcV3FactSource.toV3Facts(city(1, type, 0));
            assertFalse(facts.stationEntrance(), type + " does not occupy the surface");
            assertFalse(facts.hardBlocked(), type + " is not an obstacle");
            assertTrue(facts.roadPavable(false), type + " leaves the surface pavable for roads");
            assertTrue(facts.parcelPavable(), type + " leaves the surface eligible for buildings");
            assertFalse(facts.surfaceRail(), type + " must not be treated as a surface rail detour obstacle");
        }
    }

    @Test
    void descendingSurfaceRailIsExcludedFromBoundaryDetours() {
        V3Facts descending = TlcV3FactSource.toV3Facts(
                city(0, RailChunkType.GOING_DOWN_ONE_FROM_SURFACE, -1));
        assertFalse(descending.roadPavable(false), "the later rail pass can overwrite TLC pavement");
        assertTrue(descending.surfaceRail(), "the later rail pass can overwrite that street");
    }

    @Test
    @DisplayName("buildingEligible only follows hardBlocked, so it never blocks a road by itself")
    void buildingEligibleNeverNarrowsRoadRights() {
        // A predefined building blocks road and parcel alike, and is attributable.
        ChunkFacts building = TlcRoadFacts.chunkFacts(true, 0, true, true, false, false, false, false, false,
                RailChunkType.NONE, 0);
        V3Facts blocked = TlcV3FactSource.toV3Facts(building);
        assertTrue(blocked.hardBlocked() && blocked.predefinedBuilding() && !blocked.buildingEligible());
        assertFalse(blocked.roadPavable(false));
        assertFalse(blocked.parcelPavable());

        // A station surface is blocked for a different reason and must say so.
        V3Facts station = TlcV3FactSource.toV3Facts(city(1, RailChunkType.STATION_SURFACE, 1));
        assertTrue(station.hardBlocked());
        assertFalse(station.predefinedBuilding(), "the cause is the railway, not predefined content");
        assertFalse(station.buildingEligible());
    }

    /**
     * A same-level intercity highway is not pavable and not a bridge shore
     * ({@code ChunkFacts.pavableLand} already excludes it), because TLC renders the highway
     * itself. V3 has no highway flag of its own — it has a single frozen hard-block concept —
     * so the conflict must be folded into {@code hardBlocked}. Dropping it would plan roads
     * across a highway; publishing it as {@code predefinedBuilding} would misattribute it.
     */
    @Test
    @DisplayName("a same-level highway is published as a hard block, but not as predefined content")
    void highwayConflictIsFoldedIntoHardBlocked() {
        boolean conflict = TlcRoadFacts.highwayBlocksStreet(0, 0, -1);
        assertTrue(conflict, "fixture precondition: highway level 0 equals city level 0");
        ChunkFacts highway = TlcRoadFacts.chunkFacts(true, 0, true, false, false, conflict, false, false, false,
                RailChunkType.NONE, 0);

        V3Facts facts = TlcV3FactSource.toV3Facts(highway);
        assertTrue(facts.hardBlocked(),
                "V3 must not plan a road where TLC's own street rule refuses to pave");
        assertFalse(facts.roadPavable(false), "a highway chunk is not pavable");
        assertFalse(facts.buildingEligible(), "if it is not pavable it is not a building parcel either");
        assertFalse(facts.predefinedBuilding(),
                "a highway is not predefined content; only predefined buildings may set that flag");
    }

    @Test
    @DisplayName("a predefined street stays land, an anchor, and never a frozen obstacle")
    void predefinedStreetMappingKeepsTheAnchor() {
        ChunkFacts street = TlcRoadFacts.chunkFacts(true, 0, true, false, true, false, false, false, false,
                RailChunkType.NONE, 0);
        V3Facts facts = TlcV3FactSource.toV3Facts(street);
        assertFalse(facts.predefinedBuilding(), "a predefined street is not a predefined building");
        assertFalse(facts.hardBlocked(), "a predefined street is traversable land");
        assertTrue(facts.roadPavable(false) && facts.parcelPavable());
    }

    @Test
    @DisplayName("the predefined-building flag is exactly 'hard blocked, but not by the railway'")
    void predefinedBuildingIsRecoveredWithoutANewField() {
        // Railway-caused blocking must not be reported as a predefined building.
        V3Facts railBlocked = TlcV3FactSource.toV3Facts(
                city(1, RailChunkType.STATION_SURFACE, 1));
        assertTrue(railBlocked.hardBlocked());
        assertFalse(railBlocked.predefinedBuilding(),
                "a station surface is blocked by the railway, not by predefined content");

        // Content-caused blocking must be.
        V3Facts contentBlocked = TlcV3FactSource.toV3Facts(
                TlcRoadFacts.chunkFacts(true, 1, true, true, false, false, false, false, false,
                        RailChunkType.NONE, 0));
        assertTrue(contentBlocked.predefinedBuilding());
    }

    @Test
    @DisplayName("an empty fact maps to the V3 empty fact, and a null fact maps to empty too")
    void emptyAndNullFactsAreHandled() {
        assertEquals(V3Facts.EMPTY, TlcV3FactSource.toV3Facts(ChunkFacts.EMPTY),
                "the wilderness fact must map to the V3 empty fact");
        // The planner independently maps a null result to EMPTY as well; this is the same
        // defensive answer at the adapter boundary so neither layer can be the one that leaks it.
        assertEquals(V3Facts.EMPTY, TlcV3FactSource.toV3Facts(null),
                "a null fact is mapped to empty at the adapter boundary");
    }

    // ---------------------------------------------------------- coordinates ----

    /**
     * The planner samples {@code S+2} by default and a boundary station verdict may read a
     * second cell further out, so the adapter must answer <b>any</b> absolute coordinate
     * rather than serving from a fixed one-cell halo. This records every coordinate the
     * planner actually asks for and checks the adapter forwards them unchanged.
     */
    @Test
    @DisplayName("the adapter answers arbitrary absolute coordinates, including outside the sample window")
    void adapterForwardsAbsoluteCoordinatesWithoutAHaloArray() {
        List<int[]> requested = new ArrayList<>();
        V3FactSource source = new TlcV3FactSource((x, z) -> {
            requested.add(new int[] {x, z});
            return ChunkFacts.city(0);
        });

        // S = 10 * 3 = 30, so the supercell D[1,-1] spans chunks x 30..59, z -30..-1.
        // A fixed one-cell halo would be unable to answer x = 61 or z = -32.
        V3Planner planner = new V3Planner(31, "abstract", V3Params.selectedForDefaultArea(), source, List.of(new V3Footprint(3, 3)));
        V3Plan plan = planner.plan(new DistrictKey(1, -1));

        assertTrue(requested.size() > 0, "the planner must have sampled facts at all");
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] at : requested) {
            minX = Math.min(minX, at[0]);
            maxX = Math.max(maxX, at[0]);
            minZ = Math.min(minZ, at[1]);
            maxZ = Math.max(maxZ, at[1]);
        }
        // D[1,-1] origin is (30, -30); the sample window is origin-1 .. origin+size.
        assertEquals(29, minX, "the sample window must start one cell before the supercell origin");
        assertEquals(60, maxX, "the sample window must reach origin + size");
        assertEquals(-31, minZ, "the sample window must start one cell before the supercell origin");
        assertEquals(0, maxZ, "the sample window must reach origin + size");

        // The plan itself must be a real graph, proving the coordinates were answered, not
        // merely requested (a halo array would have returned wilderness for the outer ring).
        assertTrue(plan.count(V3RoadType.PRIMARY) + plan.count(V3RoadType.SECONDARY) > 0,
                "an all-city source must produce a road graph, so the forwarded facts were used");
        assertTrue(plan.size() == 30, "S = M * K = 10 * 3");
    }

    @Test
    @DisplayName("the same coordinate always yields the same fact, as the planner requires")
    void adapterIsAPureFunctionOfTheCoordinate() {
        TlcV3FactSource source = new TlcV3FactSource(
                (x, z) -> x == 5 && z == 7 ? ChunkFacts.blocked(1) : ChunkFacts.city(0));
        assertEquals(source.factsAt(5, 7), source.factsAt(5, 7), "repeat reads must be identical");
        assertEquals(source.factsAt(-3, -9), source.factsAt(-3, -9), "negative coordinates too");
        assertTrue(source.factsAt(5, 7).hardBlocked(), "the routed coordinate must be the one asked for");
        assertFalse(source.factsAt(7, 5).hardBlocked(), "x and z must not be transposed");
    }

    /** Guards the frozen Direction bit order the whole asset/plan ABI depends on. */
    @Test
    @DisplayName("the N/E/S/W bit order the adapter's consumers rely on is unchanged")
    void directionBitOrderIsStable() {
        assertArrayEquals(new int[] {0, 1, 2, 3}, new int[] {
                Direction.N.ordinal(), Direction.E.ordinal(),
                Direction.S.ordinal(), Direction.W.ordinal()});
        assertArrayEquals(new int[] {1, 2, 4, 8}, new int[] {
                Direction.N.bit(), Direction.E.bit(), Direction.S.bit(), Direction.W.bit()});
    }

    // ------------------------------------------------------- surface water -----

    /**
     * The defect that cut a collector in a real world: a city chunk whose pre-city floor lies
     * below the water line (a river bed the city filled and levelled) was reported as water, so
     * V3 planned no road there, and Lost Cities — which sees only "no planned road" — placed a
     * building in the hole. TLC itself puts water in a city chunk only for a drowned profile.
     */
    @Test
    @DisplayName("a filled river bed inside a city is land, not water")
    void cityChunkIsDryUnlessTheProfileIsDrowned() {
        int seaLevel = 63;
        int cityGround = 71;
        assertFalse(TlcRoadFacts.cityWater(seaLevel, cityGround),
                "a normal profile never floods a city chunk, whatever the natural floor height is");
        assertFalse(TlcRoadFacts.surfaceWater(true, seaLevel, cityGround, true),
                "the OCEAN_FLOOR height must not decide water inside a city: TLC levels it dry");
        assertTrue(TlcRoadFacts.cityWater(75, cityGround),
                "a drowned profile floods the city surface, so the surface really is water");
        assertTrue(TlcRoadFacts.surfaceWater(true, 75, cityGround, false),
                "a drowned city chunk is water even where the natural floor is above the old line");
        assertFalse(TlcRoadFacts.cityWater(cityGround, cityGround),
                "the water table must stand strictly above the ground level");
    }

    @Test
    @DisplayName("outside the city the natural water line still decides")
    void outsideChunksKeepTheNaturalWaterLine() {
        assertTrue(TlcRoadFacts.surfaceWater(false, 63, 71, true),
                "outside a city TLC keeps the vanilla terrain, so a flooded floor stays water");
        assertFalse(TlcRoadFacts.surfaceWater(false, 63, 71, false),
                "dry wilderness must not be reported as water");
    }

    /**
     * {@code belowWater} is the raw height fact and must survive the fix: bridge scanning reads
     * it, and collapsing it into {@code water} is what made a dry city chunk unpavable.
     */
    @Test
    @DisplayName("a dry city chunk keeps belowWater true while staying land")
    void dryCityChunkKeepsTheRawBelowWaterFact() {
        ChunkFacts dryCityOverOldRiverBed = TlcRoadFacts.chunkFacts(true, 0, true, false, false,
                false, false, false, true, RailChunkType.NONE, 0);
        assertTrue(dryCityOverOldRiverBed.belowWater(), "the raw height fact is still carried");
        assertFalse(dryCityOverOldRiverBed.waterCell(), "but it is not surface water");
        assertTrue(TlcV3FactSource.toV3Facts(dryCityOverOldRiverBed).land(),
                "so the planner may pave the collector across it");
    }
}
