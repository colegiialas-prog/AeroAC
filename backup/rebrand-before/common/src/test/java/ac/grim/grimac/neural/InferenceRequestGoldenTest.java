package ac.grim.grimac.neural;

import ac.grim.grimac.neural.inference.FeatureEncoder;
import ac.grim.grimac.neural.inference.InferenceJson;
import ac.grim.grimac.neural.inference.InferenceRequest;
import ac.grim.grimac.neural.inference.ModelFeature;
import ac.grim.grimac.neural.inference.ModelKind;
import ac.grim.grimac.neural.inference.ModelWindow;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Companion to ml/tests/test_service.py::test_the_canonical_java_request_is_served. This side
 * proves the client emits the fixture; that side proves the service accepts it.
 *
 * <p>Values are compared numerically rather than as text: Java and Python format floats
 * differently, and a string comparison would fail for reasons unrelated to the protocol.
 *
 * <p>Regenerate with {@code python -m aeroml.tools.make_request_golden} from ml/, then run both.
 */
class InferenceRequestGoldenTest {
    @Test void encodedRequestMatchesTheCanonicalFixture() throws Exception {
        JsonObject fixture = fixture();
        assertEquals(FeatureEncoder.FEATURE_SCHEMA_VERSION, fixture.get("featureSchemaVersion").getAsInt());
        assertEquals(ModelFeature.FEATURE_COUNT, fixture.get("featureCount").getAsInt());
        int length = fixture.get("sequenceLength").getAsInt();
        JsonArray expectedFeatures = fixture.getAsJsonArray("features");
        assertEquals((long) length * ModelFeature.FEATURE_COUNT, expectedFeatures.size());

        float[] features = new float[expectedFeatures.size()];
        for (int i = 0; i < features.length; i++) features[i] = expectedFeatures.get(i).getAsFloat();
        InferenceRequest request = InferenceRequest.of(fixture.get("requestId").getAsLong(), ModelKind.FLASH,
                ModelWindow.ATTACK, features, length, 0, 0);

        JsonObject body = new JsonParser().parse(InferenceJson.encode(request)).getAsJsonObject();
        for (String field : new String[]{"protocolVersion", "featureSchemaVersion", "requestId",
                "sequenceLength", "featureCount"}) {
            assertEquals(fixture.get(field).getAsLong(), body.get(field).getAsLong(), field);
        }
        assertEquals(fixture.get("model").getAsString(), body.get("model").getAsString());
        assertEquals(fixture.get("window").getAsString(), body.get("window").getAsString());
        JsonArray actualFeatures = body.getAsJsonArray("features");
        assertEquals(expectedFeatures.size(), actualFeatures.size());
        for (int i = 0; i < actualFeatures.size(); i++) {
            assertEquals(expectedFeatures.get(i).getAsDouble(), actualFeatures.get(i).getAsDouble(), 1.0E-6, "feature " + i);
        }
    }

    @Test void aNonFinitePayloadIsNeverPutOnTheWire() {
        float[] features = new float[2 * ModelFeature.FEATURE_COUNT];
        features[5] = Float.NaN;
        InferenceRequest request = InferenceRequest.of(1, ModelKind.FLASH, ModelWindow.ATTACK, features, 2, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> InferenceJson.encode(request));
    }

    private static JsonObject fixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        assertNotNull(root, "repository root not found");
        Path path = root.resolve("ml/tests/data/request_golden.json");
        assertTrue(Files.exists(path), "missing " + path + "; run python -m aeroml.tools.make_request_golden");
        return new JsonParser().parse(Files.readString(path)).getAsJsonObject();
    }
}
