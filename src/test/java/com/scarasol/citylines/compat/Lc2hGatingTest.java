package com.scarasol.citylines.compat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Lc2hGatingTest {
    private static Lc2hStatus status(Map<String, String> properties, String json) {
        return Lc2hStatus.evaluate(Lc2hStatus.read(true, "test", properties::get, json));
    }

    @Test
    void absentOrSupportedFastPathIsEligible() {
        assertTrue(Lc2hStatus.gate(Lc2hStatus.evaluate(Lc2hStatus.absentSignals()), "default", true).allowed());
        var fast = status(Map.of(Lc2hStatus.PROP_FAST_MULTICHUNK, "true"), "{}");
        assertFalse(fast.unsafePathActive());
        assertTrue(Lc2hStatus.gate(fast, "custom-player-profile", true).allowed());
    }

    @Test
    void experimentalAndParityPathsAreRefused() {
        for (String flag : new String[] {Lc2hStatus.PROP_CONCURRENT_BUILDING_INFO,
                Lc2hStatus.PROP_WORLD_PARITY_AUTO, Lc2hStatus.PROP_PARITY_AUTO}) {
            var gate = Lc2hStatus.gate(status(Map.of(flag, "true"), "{}"), "default", true);
            assertFalse(gate.allowed());
            assertTrue(gate.flags().contains(flag));
            assertTrue(gate.fix().contains(flag));
        }
    }

    @Test
    void upstreamModeMustBeAvailable() {
        assertFalse(Lc2hStatus.gate(status(Map.of(),
                "{\"lostCitiesStreetGenerationMode\":\"LEGACY\"}"), "default", true).allowed());
        var current = status(Map.of(), "{}");
        assertFalse(Lc2hStatus.gate(current, "AzzzCustom", true).allowed());
        assertFalse(Lc2hStatus.gate(current, "aaaaaaaaz15Flat", true).allowed());
        assertFalse(Lc2hStatus.gate(current, "default", false).allowed());
        assertFalse(Lc2hStatus.gate(current, "default", null).allowed());
    }

    @Test
    void structuredConfigurationUsesUpstreamDefaults() {
        for (String json : new String[] {null, "", "broken", "[]", "{}", 
                "{\"lostCitiesStreetGenerationMode\":7}"}) {
            assertEquals(Lc2hStatus.MODE_HIERARCHICAL_GRID_V1, status(Map.of(), json).streetGenerationMode());
        }
        assertEquals("legacy", Lc2hStatus.parseStreetMode(
                "{\"nested\":{\"lostCitiesStreetGenerationMode\":\"ignored\"},"
                + "\"lostCitiesStreetGenerationMode\":\" legacy \"}"));
        assertEquals(Lc2hStatus.MODE_LEGACY, status(Map.of(),
                "{\"lostCitiesStreetGenerationMode\":\" legacy \"}").streetGenerationMode());
    }
}

