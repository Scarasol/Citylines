package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.road.core.ChunkFacts;
import com.scarasol.citylines.road.core.FactSource;
import com.scarasol.citylines.road.core.V3FactSource;
import com.scarasol.citylines.road.core.V3Facts;

/** Adapts frozen TLC facts to the V3 planner without querying final building placement. */
public final class TlcV3FactSource implements V3FactSource {

    private final FactSource facts;

    public TlcV3FactSource(FactSource facts) {
        if (facts == null) {
            throw new IllegalArgumentException("V3 needs a frozen fact source");
        }
        this.facts = facts;
    }

    @Override
    public V3Facts factsAt(int chunkX, int chunkZ) {
        return toV3Facts(facts.factsAt(chunkX, chunkZ));
    }

    /**
     * The complete {@link ChunkFacts} to {@link V3Facts} mapping, as a pure function so it
     * can be unit-tested against real Lost Cities chunk facts without a live world.
     *
     * <p>Deliberate decisions, each of which has been a defect elsewhere in this project:
     *
     * <ul>
     *   <li>Surface station extensions are no longer road-adjudicable entrances: TLC's later
     *       railway pass clears above them even when its earlier street predicate allowed paving.</li>
     *   <li>{@code predefinedBuilding} excludes railway-caused obstacles, including underground
     *       station stairs and descent pieces, so their road gaps are not attributed to buildings.</li>
     *   <li>{@code buildingEligible} is {@link ChunkFacts#pavableLand()}: land the planner may
     *       actually build on. Per the V3 interface contract this flag only gates
     *       building-candidate rectangles and must <b>not</b> block roads on its own.</li>
     *   <li>{@code hardBlocked} folds in the <b>same-level highway conflict</b>. V3 has a
     *       single frozen hard-block concept and no separate highway flag, while TLC refuses
     *       to pave the chunk itself; mapping the conflict to "pavable" would plan roads
     *       across a highway. It is <em>not</em> reported as {@code predefinedBuilding},
     *       because a highway is not predefined content and the attribution would be a lie.
     *       (Bridges over a highway are therefore out of scope until V3 grows a highway
     *       notion of its own — recorded in {@code docs/DELIVERY.md}.)</li>
     *   <li>{@code land} is passed through rather than recomputed from
     *       {@code waterCell}/{@code belowWater}: {@code TlcRoadFacts} already applies the
     *       profile-sea-level precedence there (a fixed defect), and re-deriving it here
     *       would create a second, weaker copy of that rule.</li>
     * </ul>
     */
    public static V3Facts toV3Facts(ChunkFacts facts) {
        if (facts == null) {
            return V3Facts.EMPTY;
        }
        boolean railBlocked = facts.railBlocksStreet();
        boolean hardBlocked = facts.hardBlocked() || facts.highwayConflict();
        return new V3Facts(
                facts.cityRaw(),
                facts.cityLevel(),
                facts.land(),
                hardBlocked,
                facts.stationEntrance() && !RailStationRoadPriority.occupiesRoadSurface(
                        facts.rail(), facts.railHeight(), facts.cityLevel()),
                facts.pavableLand(),
                facts.hardBlocked() && !railBlocked
                        && !RailStationRoadPriority.occupiesRoadSurface(
                        facts.rail(), facts.railHeight(), facts.cityLevel()),
                // TLC may pave a descending rail chunk, then overwrite that pavement with rail.
                facts.rail().isSurface()
        );
    }
}
