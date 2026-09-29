package com.scarasol.citylines.road.tlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import mcjty.lostcities.worldgen.lost.regassets.data.MultiSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scarasol.citylines.road.core.V3Params;

/**
 * Step 2 of the V3 integration checklist: verify the {@code areasize} semantics before
 * reusing {@link V3Params#selectedForDefaultArea()}.
 *
 * <p>The handover requires this because the selected V3 block is only valid for
 * {@code MultiSettings.areasize() == 10}. The reason is structural, not cosmetic:
 * {@code MultiChunk} tiles the world into {@code areasize x areasize} chunk squares and
 * sizes its own {@code buildingGrid} with that number, so a multi-building can never span
 * more than {@code areasize} chunks. V3's parcel protection strides exactly
 * {@code V3Params.areaSize()} chunks across the supercell to match that same tiling. If the
 * two disagree, V3 would protect rectangles that TLC can never fill (or miss rectangles it
 * would fill), so a mismatch must be refused rather than silently reused.
 *
 * <p>Sources verified here:
 * <ul>
 *   <li>Citylines' own {@code citylines:standard} world style, which is what a
 *       Citylines-active dimension resolves to and therefore the style that will carry V3;</li>
 *   <li>{@link MultiSettings#DEFAULT}, TLC's fallback when a world style omits the block.</li>
 * </ul>
 *
 * <p><b>Manually verified, not covered by this test</b> (see {@code docs/v3-testing.md}):
 * TLC's two shipped world styles {@code lostcities:standard} and
 * {@code lostcities:standard_everywhere} both declare {@code areasize: 10} in the
 * dependency jar, the default {@link mcjty.lostcities.config.LostCityProfile} uses
 * {@code worldStyle = "standard"}, and no shipped profile JSON in
 * {@code config/lostcities/profiles/} declares a {@code multisettings} override. A user
 * datapack that ships a world style with a different {@code areasize} is therefore the
 * only way to break this, which is why the integration step must read the live value and
 * fail closed rather than trusting this test.
 */
class V3AreaSemanticsTest {

    private static final int EXPECTED_AREA_SIZE = 10;

    @Test
    @DisplayName("TLC's MultiSettings default tile is 10x10 chunks, the value V3 parameters assume")
    void tlcMultiSettingsDefaultMatchesTheV3ParameterBlock() {
        assertEquals(EXPECTED_AREA_SIZE, MultiSettings.DEFAULT.areasize(),
                "TLC's own default multi-building tile changed; V3Params.areaSize() must follow it");
        V3Params params = V3Params.selectedForDefaultArea();
        assertEquals(MultiSettings.DEFAULT.areasize(), params.areaSize(),
                "selectedForDefaultArea() is only valid for areasize()==" + EXPECTED_AREA_SIZE);
    }

    @Test
    @DisplayName("the Citylines/V3 world style declares the tile V3 parameters are frozen against")
    void roadWorldStyleDeclaresTheTileV3Assumes() {
        Map<String, Object> style = readJson("/data/citylines/lostcities/worldstyles/standard.json");
        Map<String, Object> multi = TestJson.object(style.get("multisettings"));
        int areasize = TestJson.intValue(multi, "areasize");
        assertEquals(EXPECTED_AREA_SIZE, areasize,
                "citylines:standard must declare areasize 10, the tile V3 params assume");
        assertEquals(areasize, V3Params.selectedForDefaultArea().areaSize(),
                "the style's multi-building tile and V3Params.areaSize() must be the same number");
        // The style's other tile setting is for scattered features and must not be confused
        // with the multi-building tile -- a wrong pick here silently changes parcel geometry.
        Map<String, Object> scattered = TestJson.object(style.get("scattered"));
        assertTrue(TestJson.intValue(scattered, "areasize") != areasize,
                "the fixture assumption changed: scattered.areasize now equals multisettings.areasize, "
                        + "so this test can no longer tell the two apart");
    }

    @Test
    @DisplayName("the V3 supercell is a whole multiple of the TLC multi-building tile")
    void supercellIsAWholeMultipleOfTheTile() {
        V3Params params = V3Params.selectedForDefaultArea();
        assertTrue(params.supercellSize() % params.areaSize() == 0,
                "the supercell must tile the world with whole " + params.areaSize() + "-chunk areas");
        assertTrue(params.supercellFactor() >= 2,
                "a single-area supercell would leave no room for cross-area primary axes");
    }

    private static Map<String, Object> readJson(String resource) {
        try (InputStream stream = V3AreaSemanticsTest.class.getResourceAsStream(resource)) {
            assertTrue(stream != null, "missing test resource " + resource);
            return TestJson.object(TestJson.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
    }
}
