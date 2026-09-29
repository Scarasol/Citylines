package com.scarasol.citylines.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.scarasol.citylines.CitylinesMod;
import com.scarasol.citylines.compat.Lc2hStatus;
import com.scarasol.citylines.compat.ModCompat;
import com.scarasol.citylines.road.core.DistrictKey;
import com.scarasol.citylines.road.core.V3AxisGap;
import com.scarasol.citylines.road.core.V3CellInfo;
import com.scarasol.citylines.road.core.V3Plan;
import com.scarasol.citylines.road.core.V3RoadType;
import com.scarasol.citylines.road.core.V3Planner;
import com.scarasol.citylines.road.tlc.CitylinesRoadState;
import com.scarasol.citylines.road.tlc.V3RoadGraph;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.street.PlannedRoadType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * {@code /citylines roads [radius]} — the Citylines diagnostics command.
 *
 * <p>Server-side and permission level 2 (the same gate level TLC uses for its own
 * debug tooling). Without an argument it prints the state of the caller's current
 * chunk; with a radius it sweeps a {@code (2r+1)^2} square of chunks and prints
 * aggregate counters. Output is one {@code key=value} line per claim so a log or a
 * bug report can be grepped directly.
 */
@Mod.EventBusSubscriber(modid = CitylinesMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CitylinesCommands {

    public static final String ROOT = "citylines";
    public static final String ROADS = "roads";
    public static final String CHUNK = "chunk";
    public static final String SWEEP = "sweep";
    public static final int REQUIRED_PERMISSION_LEVEL = 2;
    public static final int MAX_RADIUS = 16;
    /** Upper bound on a diagnostic sweep, so one command cannot flood the chat. */
    public static final int MAX_SWEEP_CHUNKS = 4096;

    private CitylinesCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /** Registers {@code /citylines roads [radius]}; the shape follows TLC's own {@code ModCommands}. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal(SWEEP)
                        .then(Commands.argument("x0", IntegerArgumentType.integer())
                                .then(Commands.argument("z0", IntegerArgumentType.integer())
                                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                        .executes(context -> sweepChunks(context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "x0"),
                                                                IntegerArgumentType.getInteger(context, "z0"),
                                                                IntegerArgumentType.getInteger(context, "x1"),
                                                                IntegerArgumentType.getInteger(context, "z1"))))))))
                .then(Commands.literal(CHUNK)
                        .executes(context -> chunkDetail(context.getSource(), Integer.MIN_VALUE, Integer.MIN_VALUE))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> chunkDetail(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "x"),
                                                IntegerArgumentType.getInteger(context, "z"))))))
                .then(Commands.literal(ROADS)
                        .executes(context -> report(context.getSource(), 0))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                                .executes(context -> report(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius"))))));
        CitylinesMod.LOGGER.info("[citylines] registered /{} with subcommands: {} (permission level {})",
                ROOT, String.join(", ", ROADS, CHUNK, SWEEP), REQUIRED_PERMISSION_LEVEL);
    }

    private static int diagnose(CommandSourceStack source, String subcommand, IntSupplier action) {
        try {
            return action.getAsInt();
        } catch (RuntimeException exception) {
            CitylinesMod.LOGGER.error("[citylines] /citylines {} diagnostic failed", subcommand, exception);
            source.sendFailure(Component.literal("[citylines] " + subcommand + " failed ("
                    + exception.getClass().getSimpleName() + "); see latest.log"));
            return 0;
        }
    }

    private static int report(CommandSourceStack source, int radius) {
        return diagnose(source, ROADS, () -> reportUnchecked(source, radius));
    }

    private static int reportUnchecked(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        BlockPos position = BlockPos.containing(source.getPosition());
        int chunkX = position.getX() >> 4;
        int chunkZ = position.getZ() >> 4;
        String dimension = level.dimension().location().toString();

        IDimensionInfo info = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        CitylinesRoadState state = info == null ? null : CitylinesRoadState.of(info);
        boolean active = state != null && state.isActive();

        String reason = state == null
                ? (info == null ? "no Lost Cities dimension info for this level" : "no Citylines state attached")
                : state.decisionReason();
        line(source, "MODE=" + (active ? "V3" : "TLC") + " dim=" + dimension + " chunk=(" + chunkX + "," + chunkZ + ")"
                + " profile=" + (info == null || info.getProfile() == null ? "n/a" : info.getProfile().getName())
                + " reason=\"" + reason + "\"");

        // LC2H detection: always printed, present or not, so "looks like Citylines but is TLC" cannot hide.
        Lc2hStatus lc2h = ModCompat.lc2hStatus();
        line(source, "LC2H " + lc2h.describe());

        if (!active) {
            line(source, "graph=not-applicable (planner inactive for this dimension; "
                    + "TLC roads are generated by Lost Cities)");
            return 1;
        }

        V3Plan plan = state.v3PlanFor(chunkX, chunkZ);
        V3CellInfo cell = plan.infoAt(chunkX, chunkZ);
        line(source, "V3 " + plan.key() + " size=" + plan.size()
                + " fingerprint=" + Long.toHexString(plan.fingerprint())
                + " digest=" + Long.toHexString(plan.digest()));
        line(source, "chunk class=" + cell.roadType() + " mask=0x" + Integer.toHexString(cell.edgeMask())
                + " end=" + cell.endReason() + " transition=0x" + Integer.toHexString(cell.transitionMask())
                + " roadWinsStation=" + cell.roadWinsStation());
        line(source, "edges N=" + cell.edge(com.scarasol.citylines.road.core.Direction.N)
                + " E=" + cell.edge(com.scarasol.citylines.road.core.Direction.E)
                + " S=" + cell.edge(com.scarasol.citylines.road.core.Direction.S)
                + " W=" + cell.edge(com.scarasol.citylines.road.core.Direction.W));
        line(source, "counts roads=" + plan.roadCount()
                + " P/S/T=" + plan.count(V3RoadType.PRIMARY) + "/" + plan.count(V3RoadType.SECONDARY)
                + "/" + plan.count(V3RoadType.TERTIARY));
        line(source, "stats " + describeStats(plan.stats()));
        line(source, "axisGaps n=" + plan.axisGaps().size() + " [" + describeGaps(plan.axisGaps()) + "]");
        V3Planner planner = state.v3Planner();
        line(source, "cache plans=" + planner.cachedPlans()
                + " capacity=" + V3Planner.DEFAULT_PLAN_CAPACITY);
        line(source, "multiTile areasize=" + state.multiAreaSize()
                + " v3AreaSize=" + state.v3Params().areaSize()
                + " match=" + (state.multiAreaSize() == state.v3Params().areaSize()));

        if (radius > 0) {
            sweep(source, state, chunkX, chunkZ, radius);
        }
        return 1;
    }

    /**
     * Aggregate counters over a square of chunks. Whole supercells are counted once, however many
     * of the swept chunks fall inside them.
     */
    private static void sweep(CommandSourceStack source, CitylinesRoadState state, int centerX, int centerZ,
                              int radius) {
        int size = state.v3Params().supercellSize();
        Set<DistrictKey> districts = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                districts.add(DistrictKey.of(centerX + dx, centerZ + dz, size));
            }
        }
        int chunks = (2 * radius + 1) * (2 * radius + 1);
        int primary = 0;
        int secondary = 0;
        int tertiary = 0;
        int localEntrances = 0;
        int gaps = 0;
        int factSamples = 0;
        for (DistrictKey key : districts) {
            V3Plan plan = state.v3Planner().plan(key);
            primary += plan.count(V3RoadType.PRIMARY);
            secondary += plan.count(V3RoadType.SECONDARY);
            tertiary += plan.count(V3RoadType.TERTIARY);
            gaps += plan.axisGaps().size();
            factSamples += plan.stats().factSamples();
            for (int lz = 0; lz < plan.size(); lz++) {
                for (int lx = 0; lx < plan.size(); lx++) {
                    if (plan.roadTypeAt(key.originX(size) + lx, key.originZ(size) + lz) == V3RoadType.TERTIARY
                            && plan.transitionMaskAt(key.originX(size) + lx, key.originZ(size) + lz) != 0) {
                        localEntrances++;
                    }
                }
            }
        }
        line(source, "sweep center=(" + centerX + "," + centerZ + ") radius=" + radius
                + " chunks=" + chunks + " supercells=" + districts.size()
                + " P/S/T=" + primary + "/" + secondary + "/" + tertiary
                + " localEntrances=" + localEntrances
                + " axisGaps=" + gaps + " factSamples=" + factSamples
                + " cachePlans=" + state.v3Planner().cachedPlans());
    }

    private static String describeGaps(List<V3AxisGap> gaps) {
        List<String> parts = new ArrayList<>(gaps.size());
        for (V3AxisGap gap : gaps) {
            parts.add("(" + gap.startX() + "," + gap.startZ() + ")-(" + gap.endX() + "," + gap.endZ()
                    + "):" + gap.reason() + (gap.connectedAround() ? ":around" : ":open"));
        }
        return String.join(" ", parts);
    }

    private static String describeStats(V3Plan.Stats stats) {
        return "facts=" + stats.factSamples() + " areas=" + stats.preLocalClosedAreas()
                + "+" + stats.preLocalOpenAreas() + " through=" + stats.localThrough()
                + " spurs=" + stats.localSpurs() + " merges=" + stats.mergedSegments()
                + " detours=" + stats.detours() + " lostParcels=" + stats.lostParcelAreas()
                + " unrepairedGaps=" + stats.unrepairedAxisGaps() + " budgetExhausted="
                + stats.budgetExhausted() + " droppedIsolated=" + stats.droppedIsolated()
                + " rare rescue/suppressed=" + stats.rareRescues() + "/" + stats.rareSuppressedLocals()
                + " stationWins=" + stats.stationWins();
    }


    /**
     * Per-chunk decision dump: why this chunk did or did not become a road, and whether a building
     * took it.
     *
     * <p>Written to answer one question without guessing: a road that appears in the plan but not in
     * the world can be lost at several distinct points, and each point has a different fix. Printing
     * them side by side distinguishes them:
     * <ul>
     *   <li>the plan's own answer ({@code V3 class/mask/transition});</li>
     *   <li>whether the plan reserves the chunk as a road at all (a class with no explicit edge does
     *       not count, which is what V3's own predicate says);</li>
     *   <li>Lost Cities' resolved {@code plannedRoadType} after every veto, plus its raw value and
     *       the inputs that decide it ({@code isCity}, {@code multiPos}, predefined content);</li>
     *   <li>whether the chunk ended up carrying a building, which is what visually "blocks" a road.</li>
     * </ul>
     */
    private static int chunkDetail(CommandSourceStack source, int argX, int argZ) {
        return diagnose(source, CHUNK, () -> chunkDetailUnchecked(source, argX, argZ));
    }

    private static int chunkDetailUnchecked(CommandSourceStack source, int argX, int argZ) {
        ServerLevel level = source.getLevel();
        BlockPos position = BlockPos.containing(source.getPosition());
        int chunkX = argX == Integer.MIN_VALUE ? position.getX() >> 4 : argX;
        int chunkZ = argZ == Integer.MIN_VALUE ? position.getZ() >> 4 : argZ;

        IDimensionInfo info = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        CitylinesRoadState state = info == null ? null : CitylinesRoadState.of(info);
        boolean active = state != null && state.isActive();
        line(source, "chunk (" + chunkX + "," + chunkZ + ") MODE=" + (active ? "V3" : "TLC")
                + (active ? "" : " reason=\"" + (state == null ? "no state" : state.decisionReason()) + "\""));

        V3CellInfo cell = null;
        if (active) {
            var plan = state.v3PlanFor(chunkX, chunkZ);
            cell = plan.infoAt(chunkX, chunkZ);
            line(source, "  plan: supercell=" + plan.key() + " class=" + cell.roadType()
                    + " mask=0x" + Integer.toHexString(cell.edgeMask())
                    + " transition=0x" + Integer.toHexString(cell.transitionMask())
                    + " end=" + cell.endReason());
            line(source, "  plan reserves this chunk as a road: " + V3RoadGraph.isReservedRoad(cell)
                    + "   (needs a class AND at least one explicit logical edge)");
            var entry = V3RoadGraph.partEntry(cell);
            line(source, "  surface part: " + (entry == null ? "<none - TLC would cover this chunk>"
                    : entry.part() + " rot=" + entry.quarterTurns()));
        }

        try {
            var key = new ChunkCoord(info == null ? level.dimension() : info.getType(), chunkX, chunkZ);
            var characteristics = BuildingInfo.getChunkCharacteristics(key, info);
            line(source, "  TLC: isCity=" + characteristics.isCity
                    + " rawRoad=" + characteristics.rawPlannedRoadType
                    + " effectiveRoad=" + characteristics.plannedRoadType
                    + " couldHaveBuilding=" + characteristics.couldHaveBuilding
                    + " multiPos=" + characteristics.multiPos);
            var building = BuildingInfo.getBuildingInfo(key, info);
            line(source, "  TLC: hasBuilding=" + building.hasBuilding
                    + " plannedRoadType=" + building.plannedRoadType
                    + " isPlannedRoad=" + building.isPlannedRoad());
            line(source, "  terrain: city=" + building.isCity + " level=" + building.cityLevel
                    + " ground=" + building.getCityGroundLevel());
            reportHeightmaps(source, chunkX, chunkZ);
            if (cell != null && cell.roadType().isRoad() && !V3RoadGraph.isReservedRoad(cell)) {
                line(source, "  >>> the plan gives this chunk a road class but NO explicit edge, so it is"
                        + " not a road for any consumer and a building may take it");
            } else if (cell != null && V3RoadGraph.isReservedRoad(cell)
                    && !building.plannedRoadType.equals(PlannedRoadType.NONE)) {
                line(source, "  >>> road and Lost Cities agree: this chunk is a street");
            } else if (cell != null && V3RoadGraph.isReservedRoad(cell)) {
                line(source, "  >>> MISMATCH: the plan reserves a road here but Lost Cities resolved "
                        + building.plannedRoadType + " (isCity=" + characteristics.isCity
                        + ", multiPos=" + characteristics.multiPos + ")");
            }
        } catch (RuntimeException e) {
            line(source, "  TLC characteristics unavailable here: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        return 1;
    }

    /** On-demand, read-only check; never loads neighbors or repairs the observed values. */
    private static void reportHeightmaps(CommandSourceStack source, int chunkX, int chunkZ) {
        var chunk = source.getLevel().getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            line(source, "  heightmaps: chunk not loaded");
            return;
        }
        for (var type : new net.minecraft.world.level.levelgen.Heightmap.Types[] {
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR,
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES}) {
            if (!chunk.hasPrimedHeightmap(type)) {
                line(source, "  heightmap " + type + ": not initialized");
                continue;
            }
            int mismatches = 0, maxError = 0, minTop = Integer.MAX_VALUE, maxTop = Integer.MIN_VALUE;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int actual = chunk.getMinBuildHeight();
                    for (int y = chunk.getMaxBuildHeight() - 1; y >= chunk.getMinBuildHeight(); y--) {
                        if (type.isOpaque().test(chunk.getSection(chunk.getSectionIndex(y))
                                .getBlockState(x, y & 15, z))) {
                            actual = y + 1;
                            break;
                        }
                    }
                    int error = Math.abs(chunk.getHeight(type, x, z) + 1 - actual);
                    if (error != 0) mismatches++;
                    maxError = Math.max(maxError, error);
                    minTop = Math.min(minTop, actual - 1);
                    maxTop = Math.max(maxTop, actual - 1);
                }
            }
            line(source, "  heightmap " + type + ": mismatches=" + mismatches + " maxError=" + maxError
                    + " actualTop=" + minTop + ".." + maxTop);
        }
    }

    /**
     * One line per chunk over a rectangle: plan class, mask, TLC's resolved road type and whether a
     * building took the chunk.
     *
     * <p>Written because a road can be lost at several distinct points and a single-chunk probe has
     * to be repeated by hand to see a pattern. The columns are chosen so the failure mode is
     * readable across a run of chunks:
     * <ul>
     *   <li>{@code plan} — what the plan holds ({@code P}/{@code S}/{@code t}/{@code .});</li>
     *   <li>{@code mask} — the explicit logical edges; {@code 0} means no consumer treats it as a
     *       road, whatever the class says;</li>
     *   <li>{@code eff} — Lost Cities' resolved {@code plannedRoadType} after every veto;</li>
     *   <li>{@code bld} — whether the chunk carries a building, i.e. whether a building took the
     *       cell. A {@code mask=0} next to {@code bld=y} is the "building on a planned road" case.</li>
     * </ul>
     */
    private static int sweepChunks(CommandSourceStack source, int x0, int z0, int x1, int z1) {
        return diagnose(source, SWEEP, () -> sweepChunksUnchecked(source, x0, z0, x1, z1));
    }

    private static int sweepChunksUnchecked(CommandSourceStack source, int x0, int z0, int x1, int z1) {
        ServerLevel level = source.getLevel();
        IDimensionInfo info = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        CitylinesRoadState state = info == null ? null : CitylinesRoadState.of(info);
        boolean active = state != null && state.isActive();
        int loX = Math.min(x0, x1);
        int hiX = Math.max(x0, x1);
        int loZ = Math.min(z0, z1);
        int hiZ = Math.max(z0, z1);
        if ((long) (hiX - loX + 1) * (hiZ - loZ + 1) > MAX_SWEEP_CHUNKS) {
            line(source, "sweep too large: " + ((hiX - loX + 1) * (hiZ - loZ + 1))
                    + " chunks, limit " + MAX_SWEEP_CHUNKS);
            return 0;
        }
        line(source, "sweep x=" + loX + ".." + hiX + " z=" + loZ + ".." + hiZ + " MODE=" + (active ? "V3" : "TLC"));
        for (int z = loZ; z <= hiZ; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = loX; x <= hiX; x++) {
                if (!active) {
                    row.append('?');
                    continue;
                }
                V3CellInfo cell = state.v3Cell(x, z);
                row.append(switch (cell.roadType()) {
                    case PRIMARY -> 'P';
                    case SECONDARY -> 'S';
                    case TERTIARY -> 't';
                    case NONE -> '.';
                });
            }
            line(source, "  plan  z=" + z + " " + row);
        }
        for (int z = loZ; z <= hiZ; z++) {
            StringBuilder maskRow = new StringBuilder();
            StringBuilder effRow = new StringBuilder();
            StringBuilder bldRow = new StringBuilder();
            for (int x = loX; x <= hiX; x++) {
                try {
                    var key = new ChunkCoord(info == null ? level.dimension() : info.getType(), x, z);
                    var characteristics = BuildingInfo.getChunkCharacteristics(key, info);
                    var building = BuildingInfo.getBuildingInfo(key, info);
                    int mask = active ? state.v3Cell(x, z).edgeMask() : 0;
                    maskRow.append(mask == 0 ? '.' : Integer.toHexString(mask));
                    effRow.append(switch (characteristics.plannedRoadType) {
                        case PRIMARY -> 'P';
                        case SECONDARY -> 'S';
                        case TERTIARY -> 't';
                        case NONE -> '.';
                    });
                    bldRow.append(building.hasBuilding ? 'y' : '.');
                } catch (RuntimeException e) {
                    maskRow.append('?'); effRow.append('?'); bldRow.append('?');
                }
            }
            line(source, "  mask  z=" + z + " " + maskRow);
            line(source, "  eff   z=" + z + " " + effRow + "   bld=" + bldRow);
        }
        return 1;
    }

    private static void line(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("[citylines] " + text), false);
    }
}
