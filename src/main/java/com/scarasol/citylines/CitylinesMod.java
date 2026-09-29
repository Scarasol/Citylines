package com.scarasol.citylines;

import com.mojang.logging.LogUtils;
import com.scarasol.citylines.config.CitylinesConfig;
import com.scarasol.citylines.road.RoadHooks;
import com.scarasol.citylines.terrain.TerrainFlattening;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** Three-tier streets and uniform city ground for supported Lost Cities dimensions. */
@Mod(CitylinesMod.MODID)
public final class CitylinesMod {

    public static final String MODID = "citylines";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CitylinesMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CitylinesConfig.SPEC);
        RoadHooks.bootstrap(modEventBus);
        MinecraftForge.EVENT_BUS.addListener(TerrainFlattening::onLevelLoad);
        MinecraftForge.EVENT_BUS.addListener(TerrainFlattening::onLevelUnload);
    }
}
