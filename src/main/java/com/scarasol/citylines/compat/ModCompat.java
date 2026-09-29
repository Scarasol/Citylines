package com.scarasol.citylines.compat;

import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Optional co-resident mod detection.
 *
 * <p>No reflection is used anywhere: presence is decided through Forge's mod list,
 * LC2H's switches are read with plain {@link System#getProperty(String)} calls and its
 * JSON config file is read as text. Every optional integration lives behind one of
 * these checks.
 */
public final class ModCompat {

    public static final String LOST_CITIES = "lostcities";
    public static final String LC2H = "lc2h";
    public static final String EXTRACTION_CITIES = "extractioncities";

    private ModCompat() {
    }

    public static boolean isLostCitiesLoaded() {
        return ModList.get().isLoaded(LOST_CITIES);
    }

    public static boolean isLc2hLoaded() {
        return ModList.get().isLoaded(LC2H);
    }

    public static boolean isExtractionCitiesLoaded() {
        return ModList.get().isLoaded(EXTRACTION_CITIES);
    }

    /** Version string as declared by the mod container, or {@code "unknown"}. */
    public static String versionOf(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    /**
     * A fresh read of every LC2H-side signal: mod presence, the five system properties
     * and LC2H's own JSON config. Called once per dimension initialisation and per
     * {@code /citylines roads} invocation, never per chunk.
     *
     * <p>The reads themselves are injectable ({@link Lc2hStatus#read}), so the gating
     * decision is unit-testable from plain values without mutating system properties or
     * files behind the running game's back.
     */
    public static Lc2hStatus.Signals readLc2hSignals() {
        boolean present = isLc2hLoaded();
        return Lc2hStatus.read(present, present ? versionOf(LC2H) : "",
                System::getProperty, readLc2hConfigText());
    }

    /** Evaluated LC2H detection result for the current process. */
    public static Lc2hStatus lc2hStatus() {
        return Lc2hStatus.evaluate(readLc2hSignals());
    }

    /**
     * Text of {@code config/lc2h/lc2h_config.json} relative to the game directory, or
     * {@code null} when it is missing, unreadable or not valid text. Citylines never
     * writes another mod's configuration; a {@code null} result means "LC2H's own
     * default applies" and never disables Citylines on its own.
     */
    private static String readLc2hConfigText() {
        Path path = Path.of(Lc2hStatus.CONFIG_FILE);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            // A garbled or unreadable file is tolerated: the caller treats an absent
            // value as LC2H's own HIERARCHICAL_GRID_V1 default (LC2H's
            // LostCitiesStreetModePolicy.parse does the same for blank/garbled input).
            return null;
        }
    }
}
