package com.scarasol.citylines.road.tlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scarasol.citylines.road.core.ChunkFacts;
import mcjty.lostcities.api.RailChunkType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure Lost Cities input decisions of {@link TlcRoadFacts}.
 *
 * <p>{@code TlcRoadFacts.factsAt} needs a live {@code IDimensionInfo} and cannot be
 * unit-tested here, so the decisions that were defective are extracted into pure
 * predicates ({@code highwayBlocksStreet}, {@code railBlocksStreet},
 * {@code chunkFacts}) and covered directly. The predicates replicate TLC's
 * {@code LostCityTerrainFeature.generateStreet} L1169-1172 street-suppression rule.
 */
class TlcRoadFactsRulesTest {

    // ---------------------------------------------------------- 4(a) anchors ---

    @Test
    @DisplayName("a predefined street is traversable land and a usable anchor, never a hard obstacle")
    void predefinedStreetIsNotHardBlocked() {
        ChunkFacts street = TlcRoadFacts.chunkFacts(true, 0, true, false, true, false, false, false, false, RailChunkType.NONE, 0);
        assertFalse(street.hardBlocked(), "a predefined street must not be a frozen obstacle");
        assertTrue(street.predefinedStreet(), "the anchor flag must survive");
        assertTrue(street.pavableLand(), "a predefined street must be land the planner may use");
        assertTrue(street.bridgeShore(), "a predefined street at level 0 may host a bridge head");
    }

    @Test
    @DisplayName("a predefined building is a hard obstacle; a street over a building stays one")
    void predefinedBuildingIsHardBlocked() {
        ChunkFacts building = TlcRoadFacts.chunkFacts(true, 0, true, true, false, false, false, false, false, RailChunkType.NONE, 0);
        assertTrue(building.hardBlocked(), "a predefined building is a frozen obstacle");
        assertFalse(building.pavableLand());

        ChunkFacts both = TlcRoadFacts.chunkFacts(true, 0, true, true, true, false, false, false, false, RailChunkType.NONE, 0);
        assertTrue(both.hardBlocked(), "a building wins over a street in the same chunk");
        assertTrue(both.predefinedStreet(), "the street flag may still be recorded");
    }

    @Test
    @DisplayName("a surface station is a hard obstacle; an underground station keeps its surface street")
    void stationSurfaceIsHardObstacleButUndergroundIsNot() {
        assertTrue(TlcRoadFacts.railBlocksStreet(0, RailChunkType.STATION_SURFACE, 0));
        assertTrue(TlcRoadFacts.railBlocksStreet(1, RailChunkType.STATION_SURFACE, 1));

        // TLC only suppresses the surface extension at or above the city level.
        assertTrue(TlcRoadFacts.railBlocksStreet(1, RailChunkType.STATION_EXTENSION_SURFACE, 1));
        assertTrue(TlcRoadFacts.railBlocksStreet(1, RailChunkType.STATION_EXTENSION_SURFACE, 2));
        assertFalse(TlcRoadFacts.railBlocksStreet(2, RailChunkType.STATION_EXTENSION_SURFACE, 1),
                "a surface extension below the city level still gets its street");

        // This asserts only TLC's initial street predicate. Its later staircase pass
        // still occupies the surface, as stationFlagsReachChunkFacts checks below.
        assertFalse(TlcRoadFacts.railBlocksStreet(0, RailChunkType.STATION_UNDERGROUND, 0));
        assertFalse(TlcRoadFacts.railBlocksStreet(0, RailChunkType.NONE, 0));
    }

    /**
     * TLC's initial street predicate, before its later railway geometry is written.
     *
     * <p>A surface entrance that TLC would still pave over is a genuinely ambiguous square:
     * it is an entrance to the underground network <em>and</em> eligible initial pavement.
     * The V3 adapter separately excludes the later rail geometry. Every other station type must resolve to
     * exactly one of "hard obstacle" or "nothing special" — never to an entrance. In
     * particular {@code STATION_SURFACE} is a hard obstacle and must <b>not</b> be published
     * as an entrance, which is the mistake the handover calls out explicitly.
     */
    @Test
    @DisplayName("only a surface extension below the city level is an adjudicable entrance")
    void stationEntranceIsTheStrictComplementOfTheRailStreetBlock() {
        // The one entrance case: entrance square, TLC paves it, not an obstacle.
        ChunkFacts entrance = ChunkFacts.city(2, RailChunkType.STATION_EXTENSION_SURFACE, 1);
        assertTrue(entrance.stationEntrance(), "a surface extension below the city level is an entrance");
        assertFalse(entrance.railBlocksStreet(), "TLC lays pavement there, so it must not block the street");
        assertFalse(entrance.hardBlocked(), "an adjudicable entrance is not a frozen obstacle");
        assertTrue(entrance.pavableLand(), "the square is eligible pavement, which is why it is debateable");

        // STATION_SURFACE: TLC never paves it -> hard obstacle, never an entrance.
        for (int cityLevel = 0; cityLevel <= 2; cityLevel++) {
            ChunkFacts surface = ChunkFacts.city(cityLevel, RailChunkType.STATION_SURFACE, cityLevel);
            assertTrue(surface.railBlocksStreet(), "a surface station always blocks the street");
            assertFalse(surface.stationEntrance(),
                    "a hard obstacle must never masquerade as an adjudicable entrance (cityLevel="
                            + cityLevel + ")");
        }

        // Surface extension at or above the city level: blocks the street, so also not an entrance.
        assertTrue(ChunkFacts.city(1, RailChunkType.STATION_EXTENSION_SURFACE, 1).railBlocksStreet());
        assertFalse(ChunkFacts.city(1, RailChunkType.STATION_EXTENSION_SURFACE, 1).stationEntrance());
        assertFalse(ChunkFacts.city(1, RailChunkType.STATION_EXTENSION_SURFACE, 2).stationEntrance());

        // At this raw-predicate layer, underground types allow initial pavement.
        for (RailChunkType type : new RailChunkType[] {
                RailChunkType.STATION_UNDERGROUND, RailChunkType.STATION_EXTENSION_UNDERGROUND,
                RailChunkType.NONE, RailChunkType.HORIZONTAL}) {
            ChunkFacts underground = ChunkFacts.city(1, type, 0);
            assertFalse(underground.stationEntrance(),
                    type + " does not occupy the surface and must not be an entrance");
            assertTrue(underground.pavableLand(), type + " leaves the surface pavable");
        }
    }

    @Test
    @DisplayName("the later station stairs block V3 even where TLC permits an initial street")
    void stationFlagsReachChunkFacts() {
        ChunkFacts underground = TlcRoadFacts.chunkFacts(true, 0, true, false, false, false,
                TlcRoadFacts.railBlocksStreet(0, RailChunkType.STATION_UNDERGROUND, 0), false, false,
                RailChunkType.STATION_UNDERGROUND, 0);
        assertFalse(underground.pavableLand(), "TLC later writes the staircase over that street");

        ChunkFacts surface = TlcRoadFacts.chunkFacts(true, 0, true, false, false, false,
                TlcRoadFacts.railBlocksStreet(0, RailChunkType.STATION_SURFACE, 0), false, false,
                RailChunkType.STATION_SURFACE, 0);
        assertTrue(surface.hardBlocked());
        assertFalse(surface.pavableLand());
    }

    // --------------------------------------------------------- water level -----

    @Test
    @DisplayName("an explicit profile sea level wins over the dimension sea level")
    void waterLevelPrefersAnExplicitProfileSeaLevel() {
        // BuildingInfo L868-875: wl == -1 ? Tools.getSeaLevel(world) : wl, wl = profile.SEALEVEL.
        assertEquals(63, TlcRoadFacts.waterLevel(-1, 63), "the -1 sentinel means the world sea level");
        assertEquals(89, TlcRoadFacts.waterLevel(89, 63), "an explicit profile sea level wins");
        assertEquals(0, TlcRoadFacts.waterLevel(0, 63), "0 is an explicit level, not the sentinel");
    }

    @Test
    @DisplayName("the below-water-line test follows the chosen water level")
    void belowWaterLineFollowsTheChosenLevel() {
        // atlantis-like profile: SEALEVEL 89, city ground 75, world sea level 63.
        int profileLevel = TlcRoadFacts.waterLevel(89, 63);
        assertTrue(TlcRoadFacts.belowWaterLine(75, profileLevel),
                "terrain below an explicit sea level is flooded even though it is above the world sea level");
        assertFalse(TlcRoadFacts.belowWaterLine(75, 63),
                "the same terrain is land when only the world sea level applies");
        assertTrue(TlcRoadFacts.belowWaterLine(62, 63), "a river floor below the world sea level is water");
        assertFalse(TlcRoadFacts.belowWaterLine(63, 63), "exactly at the water line counts as land");
    }

    // ------------------------------------------------- 4(b) highway conflict ---

    @Test
    @DisplayName("a highway at the city level blocks the street, on either axis")
    void highwayAtCityLevelBlocksStreet() {
        // LostCityTerrainFeature L1170-1171: highwayXLevel != cityLevel && highwayZLevel != cityLevel.
        assertTrue(TlcRoadFacts.highwayBlocksStreet(0, 0, -1));
        assertTrue(TlcRoadFacts.highwayBlocksStreet(0, -1, 0));
        assertTrue(TlcRoadFacts.highwayBlocksStreet(1, 1, 1));
        assertFalse(TlcRoadFacts.highwayBlocksStreet(0, -1, -1), "no highway nearby");
        assertFalse(TlcRoadFacts.highwayBlocksStreet(0, 1, -1), "a highway at another level is not a conflict");
        assertFalse(TlcRoadFacts.highwayBlocksStreet(1, 0, -1), "a highway at another level is not a conflict");
    }

    @Test
    @DisplayName("a highway conflict is its own flag: not pavable, not a bridge shore, not hardBlocked")
    void highwayConflictIsNotAHardObstacle() {
        boolean conflict = TlcRoadFacts.highwayBlocksStreet(0, 0, -1);
        ChunkFacts facts = TlcRoadFacts.chunkFacts(true, 0, true, false, false, conflict, false, false, false,
                RailChunkType.NONE, 0);
        assertTrue(facts.highwayConflict(), "the highway flag must be published");
        assertFalse(facts.pavableLand(), "the planner must not plan a road under a same-level highway");
        assertFalse(facts.bridgeShore(), "a highway chunk is not a bridge shore");
        assertFalse(facts.hardBlocked(), "the highway is rendered by TLC, it is not a frozen obstacle");
    }

    @Test
    @DisplayName("the full street-suppression decision matches TLC's canDoStreetOrPark")
    void canDoStreetMatchesTlc() {
        for (int cityLevel : new int[]{0, 1}) {
            for (int highwayX : new int[]{-1, 0, 1}) {
                for (int highwayZ : new int[]{-1, 0, 1}) {
                    for (RailChunkType type : new RailChunkType[]{RailChunkType.NONE,
                            RailChunkType.STATION_SURFACE, RailChunkType.STATION_UNDERGROUND,
                            RailChunkType.STATION_EXTENSION_SURFACE}) {
                        for (int railLevel : new int[]{0, 1, 2}) {
                            boolean tlc = highwayX != cityLevel && highwayZ != cityLevel
                                    && type != RailChunkType.STATION_SURFACE
                                    && (type != RailChunkType.STATION_EXTENSION_SURFACE || railLevel < cityLevel);
                            boolean ours = !TlcRoadFacts.highwayBlocksStreet(cityLevel, highwayX, highwayZ)
                                    && !TlcRoadFacts.railBlocksStreet(cityLevel, type, railLevel);
                            assertEquals(tlc, ours, "cityLevel=" + cityLevel + " highwayX=" + highwayX
                                    + " highwayZ=" + highwayZ + " type=" + type + " railLevel=" + railLevel);
                        }
                    }
                }
            }
        }
    }
}
