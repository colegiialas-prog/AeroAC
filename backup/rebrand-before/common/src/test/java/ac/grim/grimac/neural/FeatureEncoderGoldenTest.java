package ac.grim.grimac.neural;

import ac.grim.grimac.neural.inference.FeatureEncoder;
import ac.grim.grimac.neural.inference.ModelFeature;
import ac.grim.grimac.neural.telemetry.CombatFrame;
import ac.grim.grimac.neural.telemetry.FrameField;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-language contract. ml/tests/test_features.py loads the same fixture, so this test failing
 * means the Java encoder and the training pipeline would hand the model different columns.
 *
 * <p>Regenerate with {@code python -m aeroml.tools.make_encoder_golden} from ml/, then run both
 * suites — never regenerate to make one side go green on its own.
 */
class FeatureEncoderGoldenTest {
    private static final float TOLERANCE = 1.0E-5f;

    @Test void javaReproducesEveryGoldenCase() throws Exception {
        JsonObject fixture = fixture();
        assertEquals(FeatureEncoder.FEATURE_SCHEMA_VERSION, fixture.get("featureSchemaVersion").getAsInt());
        assertEquals(CombatFrame.SCHEMA_VERSION, fixture.get("rawSchemaVersion").getAsInt());
        JsonArray rawFields = fixture.getAsJsonArray("rawFields");
        assertEquals(FrameField.COUNT, rawFields.size());
        JsonArray channels = fixture.getAsJsonArray("channels");
        String[] expectedChannels = ModelFeature.channelNames();
        assertEquals(expectedChannels.length, channels.size());
        for (int i = 0; i < expectedChannels.length; i++) {
            assertEquals(expectedChannels[i], channels.get(i).getAsString(), "channel " + i);
        }

        JsonArray cases = fixture.getAsJsonArray("cases");
        assertTrue(cases.size() >= 7, "fixture lost cases");
        for (JsonElement element : cases) {
            JsonObject testCase = element.getAsJsonObject();
            String name = testCase.get("name").getAsString();
            CombatFrame[] window = window(testCase.getAsJsonArray("raw"), rawFields);
            float[] actual = FeatureEncoder.encode(window);
            JsonArray expected = testCase.getAsJsonArray("encoded");
            assertEquals(expected.size(), window.length, name + " length");
            for (int t = 0; t < expected.size(); t++) {
                JsonArray row = expected.get(t).getAsJsonArray();
                assertEquals(ModelFeature.FEATURE_COUNT, row.size(), name + " width");
                for (int channel = 0; channel < row.size(); channel++) {
                    float want = row.get(channel).getAsFloat();
                    float got = actual[t * ModelFeature.FEATURE_COUNT + channel];
                    if (Math.abs(want - got) > Math.max(TOLERANCE, Math.abs(want) * TOLERANCE)) {
                        fail(name + " sample " + t + " channel " + expectedChannels[channel]
                                + ": expected " + want + " got " + got);
                    }
                }
            }
        }
    }

    private static CombatFrame[] window(JsonArray rows, JsonArray rawFields) {
        CombatFrame[] frames = new CombatFrame[rows.size()];
        for (int t = 0; t < rows.size(); t++) {
            JsonArray row = rows.get(t).getAsJsonArray();
            assertEquals(FrameField.COUNT, row.size());
            double[] values = new double[FrameField.COUNT];
            for (int i = 0; i < row.size(); i++) {
                assertEquals(rawFields.get(i).getAsString(), FrameField.values()[i].name());
                JsonElement value = row.get(i);
                values[i] = value.isJsonNull() ? Double.NaN : value.getAsDouble();
            }
            frames[t] = new CombatFrame(t + 1, (t + 1) * 50_000_000L, values);
        }
        return frames;
    }

    private static JsonObject fixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        assertNotNull(root, "repository root not found");
        Path path = root.resolve("ml/tests/data/encoder_golden.json");
        assertTrue(Files.exists(path), "missing " + path + "; run python -m aeroml.tools.make_encoder_golden");
        return new JsonParser().parse(Files.readString(path)).getAsJsonObject();
    }
}
