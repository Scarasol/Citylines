package com.scarasol.citylines.mixin.lc2h;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.citylines.CitylinesMod;
import com.scarasol.citylines.compat.Lc2hRoadRightsDiagnostics;
import com.scarasol.citylines.road.tlc.CitylinesRoadState;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds planned road rights to LC2H's fast multi-chunk path <b>at its single occupancy check</b>,
 * instead of switching that path off.
 *
 * <h2>Why this seam</h2>
 *
 * <p>{@code FastMultiChunkPlanner} replaces Lost Cities' {@code MultiChunk.calculateBuildings}. It
 * never runs {@code canPlaceBuilding}, which is where Lost Cities asks whether a candidate
 * footprint may be built, so a planned road could be built over unless something else refuses it.
 * Rewriting the placement algorithm is not an option: it is LC2H's code, reachable only as a jar,
 * and mirroring it would drift on every LC2H update.
 *
 * <p>What the fast path <em>does</em> ask is {@code LazyPlan.occupied(int)}: one occupancy question
 * per candidate cell, cached in a byte per cell ({@code 1} = free, {@code 2} = occupied). Its only
 * real query is a single call to {@code City.isChunkOccupied}. Answering "occupied" there is the
 * same refusal Lost Cities itself expresses, so the fast path keeps its structure, its caching and
 * its speed, and no second planning pass is needed.
 *
 * <h2>Why not the outer audit call</h2>
 *
 * <p>{@code LazyPlan} is also read by the planner's audit and trace paths. Wrapping an occupancy
 * check made by those paths would change what the audit <em>reports</em> without changing what gets
 * built, and would let a diagnostic disagree with the world. The injection therefore aims at the
 * private {@code occupied(int)} method of the inner class, which is the placement decision itself.
 *
 * <h2>Gating and safety</h2>
 *
 * <p>The handler always calls the original operation first and can only turn {@code false} into
 * {@code true}, so an inactive dimension is bit-for-bit LC2H's own behaviour and LC2H's notion of
 * occupancy keeps full force. The state is resolved through the compile-time duck interface Mixin
 * adds to Lost Cities' {@code HierarchicalStreetPlanner} — no reflection, no static registry.
 *
 * <p>The LC2H target is referenced by <b>string only</b>, this class names no LC2H type, and the
 * single {@code citylines.mixins.json} only considers this mixin when {@code CitylinesMixinPlugin}
 * sees LC2H in Forge's mod list. Only {@code @Inject} and {@code @WrapOperation} are used: no
 * {@code @Redirect}, no {@code @Overwrite}.
 *
 * <p><b>Descriptor and call site verified with {@code javap}</b> against
 * {@code lc2h-1325431-8928853} (4.2.4-LTS): {@code LazyPlan.occupied(int)} holds exactly one
 * {@code invokestatic City.isChunkOccupied(IDimensionInfo, ChunkCoord)Z}, and the result is stored
 * as {@code 2} when true and {@code 1} when false. {@code require = 1} makes a future LC2H that
 * moves or duplicates that call fail loudly at load time rather than silently stop protecting
 * roads.
 */
@Mixin(targets = "org.admany.lc2h.worldgen.lostcities.FastMultiChunkPlanner$LazyPlan", remap = false)
public abstract class Lc2hFastMultiChunkPlannerMixin {

    /**
     * The placement decision's occupancy query, with planned road rights merged in.
     *
     * @param provider the dimension the fast path is planning for
     * @param coord    the candidate chunk being tested
     * @param original LC2H's own {@code City.isChunkOccupied} call
     * @return whether the cell must be treated as occupied
     */
    @WrapOperation(method = "occupied(I)Z",
            at = @At(value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/lost/City;isChunkOccupied(Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/varia/ChunkCoord;)Z"),
            require = 1)
    private static boolean citylines$mergePlannedRoadRight(IDimensionInfo provider, ChunkCoord coord,
                                                          Operation<Boolean> original) {
        if (original.call(provider, coord)) {
            return true;
        }
        CitylinesRoadState state = CitylinesRoadState.of(provider);
        if (state == null || !state.isActive()) {
            return false;
        }
        if (!state.roadReservedAt(coord.chunkX(), coord.chunkZ())) {
            return false;
        }
        if (Lc2hRoadRightsDiagnostics.firstMerge()) {
            CitylinesMod.LOGGER.info("[Citylines/LC2H] fast multi-chunk path kept: planned road right merged at "
                            + "LazyPlan.occupied for {} (chunk {},{}), so the fast path stays enabled",
                    provider.getType().location(), coord.chunkX(), coord.chunkZ());
        }
        return true;
    }
}
