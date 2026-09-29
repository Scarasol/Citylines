package com.scarasol.citylines.terrain;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UniformBoundaryTest {
    @Test
    void rawGatesNeverApplyToNativeDimensions() {
        assertFalse(CityGroundBinding.rawGateScope(CityGroundBinding.State.NATIVE, true));
        assertTrue(CityGroundBinding.rawGateScope(CityGroundBinding.State.NOT_READY, true));
        assertTrue(CityGroundBinding.rawGateScope(CityGroundBinding.State.READY, true));
        assertFalse(CityGroundBinding.rawGateScope(CityGroundBinding.State.NOT_READY, false));
    }
    @Test
    void contentFingerprintIgnoresObjectOrderButNotContent() {
        assertEquals(hash("{\"a\":1,\"b\":2}"), hash("{\"b\":2,\"a\":1}"));
        assertNotEquals(hash("{\"same-name\":{\"radius\":8}}"),
                hash("{\"same-name\":{\"radius\":9}}"));
        assertNotEquals(hash("[1,2]"), hash("[2,1]"));
    }

    @Test
    void nestedSamplingFailureRestoresTheOngoingFill() {
        assertNull(TerrainFlattening.samplingContext());
        TerrainFlattening.withContext(null, false, () -> {
            assertEquals(false, TerrainFlattening.samplingContext());
            assertThrows(IllegalStateException.class, () -> TerrainFlattening.withContext(null, true,
                    () -> { throw new IllegalStateException("column failure"); }));
            assertEquals(false, TerrainFlattening.samplingContext());
            return null;
        });
        assertNull(TerrainFlattening.samplingContext());
    }

    @Test
    void fillFailureDoesNotLeakIntoTheNextTask() {
        assertThrows(IllegalArgumentException.class, () -> TerrainFlattening.withContext(null, false,
                () -> { throw new IllegalArgumentException("fill failure"); }));
        assertNull(TerrainFlattening.samplingContext());
        TerrainFlattening.withContext(null, true, () -> {
            assertEquals(true, TerrainFlattening.samplingContext());
            return null;
        });
        assertNull(TerrainFlattening.samplingContext());
    }

    private static String hash(String json) {
        return GenerationFingerprint.digest(JsonParser.parseString(json));
    }
}
