package com.scarasol.citylines.road.tlc;

import com.scarasol.citylines.CitylinesMod;
import com.scarasol.citylines.config.CitylinesConfig;
import com.scarasol.citylines.road.core.Direction;
import com.scarasol.citylines.road.core.RoadType;
import com.scarasol.citylines.road.core.V3CellInfo;
import com.scarasol.citylines.road.core.V3RoadType;
import mcjty.lostcities.setup.CustomRegistries;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.Transform;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.IBuildingPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;

import java.util.concurrent.atomic.AtomicInteger;

/** Places current three-tier street/bridge surfaces and keeps their ports clear of decorations. */
public final class RoadSurfaceHooks {

    private static final AtomicInteger WARNINGS = new AtomicInteger();
    private static final AtomicInteger PLACEMENTS = new AtomicInteger();

    /** How many placements are always logged at INFO, so a run proves the hook fired. */
    private static final int ALWAYS_LOGGED_PLACEMENTS = 8;

    private RoadSurfaceHooks() {
    }

    /**
     * Place the Citylines surface part for this chunk.
     *
     * @return {@code true} when the caller must cancel the TLC street section
     */
    public static boolean placeRoadSection(LostCityTerrainFeature feature, BuildingInfo info, int height) {
        V3CellInfo cell = v3Cell(info);
        if (cell == null) {
            return false;
        }
        RoadPartTable.Entry entry = V3RoadGraph.partEntry(cell);
        if (entry == null) {
            // A reachable key always resolves (the dimension pre-check refuses otherwise), so this
            // can only be an unreachable shape the planner is not supposed to publish. Do not
            // cancel: the chunk still gets a surface instead of a hole in a started dimension.
            warnOnce("no road part for key " + cell.roadType() + " mask=" + cell.edgeMask()
                    + " trans=" + cell.transitionMask() + "; TLC covers this chunk");
            return false;
        }
        IBuildingPart part = resolvePart(feature.provider.getWorld(), entry.part());
        if (part == null) {
            // The dimension pre-check normally prevents this; declining keeps the chunk
            // paved by TLC instead of leaving a hole next to already generated chunks.
            warnOnce("missing Citylines part " + entry.part() + " at chunk "
                    + info.coord.chunkX() + "," + info.coord.chunkZ() + "; TLC covers this chunk");
            return false;
        }
        feature.generatePart(info, part, transform(entry.quarterTurns()), 0, height, 0,
                LostCityTerrainFeature.HardAirSetting.VOID);
        int placed = PLACEMENTS.incrementAndGet();
        if (CitylinesConfig.INSTANCE.debugLogging() || placed <= ALWAYS_LOGGED_PLACEMENTS) {
            CitylinesMod.LOGGER.info("[citylines] road part {} rot={} at {},{} y={}",
                    entry.part(), entry.quarterTurns(), info.coord.chunkX(), info.coord.chunkZ(), height);
        }
        return true;
    }

    /**
     * True when the TLC vegetation pass must be skipped for this Citylines chunk.
     */
    public static boolean suppressVegetation(BuildingInfo info) {
        if (info == null || info.profile == null) {
            return false;
        }
        byte[] roles = placedRoles(info);
        if (roles == null) {
            return false;
        }
        int thickness = info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS;
        if (thickness <= 0) {
            return false;
        }
        // Mirror generateRandomVegetation's four zones exactly, including TLC's
        // `15 - thickness .. < 15` bounds on the east/south sides (x/z = 15 stays out).
        if (info.getXmin().hasBuilding && zoneTouchesPaved(roles, 0, thickness, 0, RoadSurfaceLayout.SIZE)) {
            return true;
        }
        if (info.getXmax().hasBuilding
                && zoneTouchesPaved(roles, RoadSurfaceLayout.SIZE - 1 - thickness, RoadSurfaceLayout.SIZE - 1,
                        0, RoadSurfaceLayout.SIZE)) {
            return true;
        }
        if (info.getZmin().hasBuilding && zoneTouchesPaved(roles, 0, RoadSurfaceLayout.SIZE, 0, thickness)) {
            return true;
        }
        return info.getZmax().hasBuilding
                && zoneTouchesPaved(roles, 0, RoadSurfaceLayout.SIZE,
                        RoadSurfaceLayout.SIZE - 1 - thickness, RoadSurfaceLayout.SIZE - 1);
    }

    /**
     * True when the TLC street decoration (entrance stairs) must be skipped for this Citylines
     * chunk because the stair footprint would cover the carriageway or a kerb.
     */
    public static boolean suppressStairs(BuildingInfo info) {
        if (info == null) {
            return false;
        }
        byte[] roles = placedRoles(info);
        if (roles == null || info.stairType == null) {
            return false;
        }
        mcjty.lostcities.worldgen.lost.Direction stairDirection = info.getActualStairDirection();
        if (stairDirection == null) {
            return false;
        }
        Transform transform = switch (stairDirection) {
            case XMIN -> Transform.ROTATE_NONE;
            case XMAX -> Transform.ROTATE_180;
            case ZMIN -> Transform.ROTATE_90;
            case ZMAX -> Transform.ROTATE_270;
        };
        IBuildingPart stairs = info.stairType;
        for (int x = 0; x < stairs.getXSize(); x++) {
            for (int z = 0; z < stairs.getZSize(); z++) {
                if (stairs.getVSlice(x, z) == null) {
                    continue;
                }
                int rx = transform.rotateX(x, z);
                int rz = transform.rotateZ(x, z);
                if (rx < 0 || rz < 0 || rx >= RoadSurfaceLayout.SIZE || rz >= RoadSurfaceLayout.SIZE) {
                    continue;
                }
                byte role = RoadSurfaceLayout.role(roles, rx, rz);
                // Every driving-surface role counts, including the local lane material and the
                // arterial centre line: the decoration must not be written over any of them.
                if (RoadSurfaceLayout.isPaved(role) || role == RoadSurfaceLayout.KERB) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The plan of a chunk that Citylines renders, or {@code null} when TLC must keep it.
     *
     * <p>Both sources must agree: the dimension runs Citylines, the Citylines plan gives the chunk a
     * road class, and Lost Cities' own characteristics also consider it a planned road
     * (so building and multi-building vetoes always win over the plan).
     */
    static V3CellInfo v3Cell(BuildingInfo info) {
        if (info == null || info.provider == null || !info.isPlannedRoad()) {
            return null;
        }
        CitylinesRoadState state = CitylinesRoadState.of(info.provider);
        if (state == null || !state.isActive()) {
            return null;
        }
        V3CellInfo cell = state.v3Cell(info.coord.chunkX(), info.coord.chunkZ());
        return cell.roadType().isRoad() ? cell : null;
    }

    /**
     * Geometric ports of the piece for a V3 cell, in world orientation.
     *
     * <p>Derived by the same rule {@link V3RoadGraph#partEntry} uses, and that is deliberate: the
     * suppression code below decides whether a decoration would land on a paved role, so it must
     * see the <b>same</b> piece the renderer places. Deriving the ports a second way here (for
     * example by clamping the local entrance arm back to the local class) would let vegetation be
     * suppressed for a walk band the placed piece actually paints as carriageway.
     */
    static RoadType[] placedPorts(V3CellInfo cell) {
        if (cell.roadType() == V3RoadType.TERTIARY) {
            RoadType[] local = {RoadType.NONE, RoadType.NONE, RoadType.NONE, RoadType.NONE};
            for (Direction direction : Direction.VALUES) {
                local[direction.ordinal()] = cell.edge(direction).isRoad() ? RoadType.TERTIARY : RoadType.NONE;
            }
            RoadType[] ports = new RoadType[Direction.COUNT];
            for (Direction direction : Direction.VALUES) {
                ports[direction.ordinal()] = RoadPartTable.localPort(local[direction.ordinal()],
                        cell.transitionArm(direction));
            }
            return ports;
        }
        RoadType[] ports = new RoadType[Direction.COUNT];
        for (Direction direction : Direction.VALUES) {
            ports[direction.ordinal()] = cell.edge(direction).isRoad()
                    ? (cell.edge(direction) == V3RoadType.PRIMARY ? RoadType.PRIMARY : RoadType.SECONDARY)
                    : RoadType.NONE;
        }
        return ports;
    }

    /**
     * Role grid of the piece that would be placed in this chunk, in world
     * orientation; {@code null} when TLC owns the chunk.
     *
     * <p>{@link RoadSurfaceLayout#grid} already takes the four directed edge classes
     * and returns the world-oriented piece, so the table's rotation (which maps the
     * canonical JSON onto that orientation) must not be applied a second time.
     */
    static byte[] placedRoles(BuildingInfo info) {
        V3CellInfo cell = v3Cell(info);
        if (cell == null || V3RoadGraph.partEntry(cell) == null) {
            return null;
        }
        RoadType centre = V3RoadGraph.toTableCentre(cell.roadType());
        if (centre == null) {
            return null;
        }
        RoadType[] ports = placedPorts(cell);
        return RoadSurfaceLayout.grid(centre, ports[0], ports[1], ports[2], ports[3]);
    }

    private static boolean zoneTouchesPaved(byte[] roles, int x0, int x1, int z0, int z1) {
        for (int z = z0; z < z1; z++) {
            for (int x = x0; x < x1; x++) {
                if (RoadSurfaceLayout.role(roles, x, z) != RoadSurfaceLayout.SKIP) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Transform transform(int quarterTurns) {
        return switch (quarterTurns & 3) {
            case 1 -> Transform.ROTATE_90;
            case 2 -> Transform.ROTATE_180;
            case 3 -> Transform.ROTATE_270;
            default -> Transform.ROTATE_NONE;
        };
    }

    /**
     * Resolve a part without ever taking the crashing path of
     * {@code RegistryAssetRegistry.get}, which rethrows for a missing registry entry.
     */
    private static IBuildingPart resolvePart(WorldGenLevel world, String name) {
        ResourceLocation id = new ResourceLocation(name);
        if (world.registryAccess().registryOrThrow(CustomRegistries.PART_REGISTRY_KEY).get(id) == null) {
            return null;
        }
        return AssetRegistries.PARTS.get(world, id);
    }

    private static void warnOnce(String message) {
        if (WARNINGS.incrementAndGet() <= 8) {
            CitylinesMod.LOGGER.error("[citylines] {}", message);
        }
    }
}
