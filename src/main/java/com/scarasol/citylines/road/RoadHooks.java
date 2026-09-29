package com.scarasol.citylines.road;

import com.scarasol.citylines.CitylinesMod;
import com.scarasol.citylines.compat.ModCompat;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Mod lifecycle entry points.
 *
 * <p>Per-dimension road logic lives in {@code CitylinesRoadState} (attached from the
 * {@code DefaultDimensionInfo} mixin); this class only reports what Citylines found
 * at startup so that a mis-matched environment is visible in the log before any
 * world is generated.
 */
public final class RoadHooks {

    /** The Lost Cities version every mixin target in this mod was written against. */
    public static final String EXPECTED_LOST_CITIES = "1.20-7.5.5";

    private RoadHooks() {
    }

    public static void bootstrap(IEventBus modEventBus) {
        modEventBus.addListener(RoadHooks::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        String lostCities = ModCompat.versionOf(ModCompat.LOST_CITIES);
        CitylinesMod.LOGGER.info("Citylines {} initialising (lostcities {}, lc2h {}, extractioncities {})",
                ModCompat.versionOf(CitylinesMod.MODID),
                lostCities,
                ModCompat.isLc2hLoaded() ? ModCompat.versionOf(ModCompat.LC2H) : "absent",
                ModCompat.isExtractionCitiesLoaded() ? ModCompat.versionOf(ModCompat.EXTRACTION_CITIES) : "absent");
        if (!EXPECTED_LOST_CITIES.equals(lostCities)) {
            CitylinesMod.LOGGER.warn("Citylines was written against Lost Cities {} but {} is loaded; "
                            + "mixin targets may not match. Verify before trusting Citylines output.",
                    EXPECTED_LOST_CITIES, lostCities);
        }
    }
}
