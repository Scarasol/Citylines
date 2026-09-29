package com.scarasol.citylines.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Current LC2H capability checks. External street-mode names are upstream API values. */
public record Lc2hStatus(boolean present, String version, String streetGenerationMode, boolean modeKnown,
                         boolean unsafePathActive, boolean modeOverrideRisk, List<String> flags, String reason) {
    public static final String PROP_CONCURRENT_BUILDING_INFO = "lc2h.concurrentBuildingInfo";
    public static final String PROP_FAST_MULTICHUNK = "lc2h.fast_multichunk.enabled";
    public static final String PROP_BASELINE_MODE = "lc2h.baselineMode";
    public static final String PROP_WORLD_PARITY_AUTO = "lc2h.worldparity.auto";
    public static final String PROP_PARITY_AUTO = "lc2h.parity.auto";
    public static final String CONFIG_FILE = "config/lc2h/lc2h_config.json";
    public static final String CONFIG_KEY_STREET_MODE = "lostCitiesStreetGenerationMode";
    public static final String MODE_HIERARCHICAL_GRID_V1 = "HIERARCHICAL_GRID_V1";
    public static final String MODE_LEGACY = "LEGACY";
    private static final String CHAOS_Z_PACK_FLAT_PROFILE = "aaaaaaaaz15Flat";
    private static final String CHAOS_Z_PACK_PREFIX = "Azzz";

    public Lc2hStatus {
        version = version == null ? "" : version;
        streetGenerationMode = streetGenerationMode == null ? "" : streetGenerationMode;
        flags = List.copyOf(flags == null ? List.of() : flags);
        reason = reason == null ? "" : reason;
    }

    public String describe() {
        return "present=" + present + " version=" + version + " streetGenerationMode=" + streetGenerationMode
                + " modeFromConfig=" + modeKnown + " unsafe=" + unsafePathActive + " modeRisk=" + modeOverrideRisk
                + " flags=" + flags + " reason=\"" + reason + "\"";
    }

    public record Signals(boolean present, String version, boolean concurrentBuildingInfo, boolean fastMultiChunk,
                          boolean baselineMode, boolean worldParityAuto, boolean parityAuto,
                          String configuredStreetMode, boolean configRead) {
    }

    public static Signals absentSignals() {
        return new Signals(false, "", false, false, false, false, false, null, false);
    }

    public static Signals read(boolean present, String version, Function<String, String> properties, String json) {
        Objects.requireNonNull(properties, "properties");
        return new Signals(present, version, flag(properties, PROP_CONCURRENT_BUILDING_INFO),
                flag(properties, PROP_FAST_MULTICHUNK), flag(properties, PROP_BASELINE_MODE),
                flag(properties, PROP_WORLD_PARITY_AUTO), flag(properties, PROP_PARITY_AUTO),
                parseStreetMode(json), json != null);
    }

    private static boolean flag(Function<String, String> properties, String key) {
        return Boolean.parseBoolean(properties.apply(key));
    }

    public static String parseStreetMode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonElement value = root.getAsJsonObject().get(CONFIG_KEY_STREET_MODE);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
            String text = value.getAsString().trim();
            return text.isEmpty() ? null : text;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Matches LC2H's own treatment of missing and unknown configuration values. */
    public static String normalizeStreetMode(String raw) {
        return raw != null && MODE_LEGACY.equals(raw.trim().toUpperCase(Locale.ROOT))
                ? MODE_LEGACY : MODE_HIERARCHICAL_GRID_V1;
    }

    /** Detection of LC2H's forced upstream mode, not a Citylines profile-specific algorithm. */
    public static boolean forcesLegacyMode(String profileName) {
        if (profileName == null) return false;
        String name = profileName.trim();
        return name.equalsIgnoreCase(CHAOS_Z_PACK_FLAT_PROFILE)
                || name.regionMatches(true, 0, CHAOS_Z_PACK_PREFIX, 0, CHAOS_Z_PACK_PREFIX.length());
    }

    public static Lc2hStatus evaluate(Signals signals) {
        if (signals == null || !signals.present()) {
            return new Lc2hStatus(false, "", MODE_HIERARCHICAL_GRID_V1, true, false, false, List.of(),
                    "LC2H not installed");
        }
        List<String> flags = new ArrayList<>();
        if (signals.concurrentBuildingInfo()) flags.add(PROP_CONCURRENT_BUILDING_INFO);
        if (signals.worldParityAuto()) flags.add(PROP_WORLD_PARITY_AUTO);
        if (signals.parityAuto()) flags.add(PROP_PARITY_AUTO);
        String mode = normalizeStreetMode(signals.configuredStreetMode());
        String reason = flags.isEmpty() ? "current LC2H paths supported" : "unsupported LC2H paths: " + flags;
        reason += "; fast multi-chunk occupancy includes Citylines road rights";
        return new Lc2hStatus(true, signals.version(), mode, signals.configRead(), !flags.isEmpty(),
                !MODE_HIERARCHICAL_GRID_V1.equals(mode), flags, reason);
    }

    public enum Outcome { ELIGIBLE, REFUSED }

    public record Gate(Outcome outcome, List<String> flags, String reason, String fix) {
        public Gate {
            flags = List.copyOf(flags);
        }

        public boolean allowed() {
            return outcome == Outcome.ELIGIBLE;
        }
    }

    public static Gate gate(Lc2hStatus status, String profileName, Boolean runtimeModeIsHv1) {
        if (status == null || !status.present()) {
            return new Gate(Outcome.ELIGIBLE, List.of(), "LC2H not installed", "");
        }
        if (status.unsafePathActive()) {
            return new Gate(Outcome.REFUSED, status.flags(), status.reason(),
                    "Disable these experimental/parity JVM options: " + String.join(", ", status.flags()));
        }
        if (status.modeOverrideRisk() || forcesLegacyMode(profileName) || !Boolean.TRUE.equals(runtimeModeIsHv1)) {
            return new Gate(Outcome.REFUSED, status.flags(),
                    "LC2H does not expose the HIERARCHICAL_GRID_V1 planner for this dimension",
                    "Use an upstream street mode/profile that exposes HIERARCHICAL_GRID_V1");
        }
        return new Gate(Outcome.ELIGIBLE, status.flags(), status.reason(), "");
    }
}
