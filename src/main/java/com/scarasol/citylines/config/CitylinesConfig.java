package com.scarasol.citylines.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Current generation options. A dimension freezes its rule before generating chunks. */
public final class CitylinesConfig {
    public static final ForgeConfigSpec SPEC;
    public static final CitylinesConfig INSTANCE;

    static {
        Pair<CitylinesConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(CitylinesConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private final ForgeConfigSpec.BooleanValue enabled;
    private final ForgeConfigSpec.BooleanValue debugLogging;

    private CitylinesConfig(ForgeConfigSpec.Builder builder) {
        enabled = builder.comment("Enable Citylines roads and uniform city ground in new supported dimensions.",
                        "Changing this after a dimension is bound refuses generation; it does not switch algorithms.")
                .define("enabled", true);
        debugLogging = builder.comment("Log bounded road and terrain diagnostics.")
                .define("debugLogging", false);
    }

    public boolean enabled() {
        return enabled.get();
    }

    public boolean debugLogging() {
        return debugLogging.get();
    }
}
