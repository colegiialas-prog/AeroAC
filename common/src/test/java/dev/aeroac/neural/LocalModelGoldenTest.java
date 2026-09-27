package dev.aeroac.neural;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.inference.InferenceRequest;
import dev.aeroac.neural.inference.InferenceResponse;
import dev.aeroac.neural.inference.ModelKind;
import dev.aeroac.neural.inference.ModelWindow;
import dev.aeroac.neural.inference.local.LocalInferenceClient;
import dev.aeroac.neural.inference.local.LocalModelBundle;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Companion to ml/tests/test_local_model.py. The fixture is a real bundle whose logits PyTorch
 * computed; the in-JVM network must reproduce them, and with them the service's normalisation and
 * calibration. Regenerate with {@code python -m aeroml.tools.make_local_model_golden}.
 */
class LocalModelGoldenTest {
    private static final double LOGIT_TOLERANCE = 1.0E-4;

    @Test void theJavaNetworkReproducesPyTorchLogitsAndProbabilities() throws Exception {
        LocalModelBundle bundle = LocalModelBundle.load(directory());
        JsonObject fixture = cases();
        assertEquals(List.of("overall", "aimAssist"), bundle.heads());
        assertTrue(bundle.calibrated());
        assertEquals(0.25, bundle.calibrationPrior(), 1.0E-12);
        for (JsonElement element : fixture.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            float[] features = floats(item.getAsJsonArray("features"));
            double[] probabilities = bundle.predict(features);
            JsonArray expected = item.getAsJsonArray("probabilities");
            for (int head = 0; head < probabilities.length; head++) {
                assertEquals(expected.get(head).getAsDouble(), probabilities[head], LOGIT_TOLERANCE, "head " + head);
            }
        }
    }

    @Test void theLocalClientAnswersWithTheSameContractAsTheService() throws Exception {
        LocalModelBundle bundle = LocalModelBundle.load(directory());
        JsonObject first = cases().getAsJsonArray("cases").get(0).getAsJsonObject();
        float[] features = floats(first.getAsJsonArray("features"));
        try (LocalInferenceClient client = new LocalInferenceClient(List.of(bundle), 2, 1)) {
            assertTrue(client.admit());
            InferenceResponse response = client.infer(InferenceRequest.of(7, ModelKind.FLASH, ModelWindow.ATTACK,
                    features, bundle.sequenceLength(), 0, 1)).get(5, TimeUnit.SECONDS);
            assertEquals(7, response.requestId());
            assertEquals(FeatureEncoder.FEATURE_SCHEMA_VERSION, response.featureSchemaVersion());
            assertEquals("golden-local-v1", response.modelVersion());
            assertEquals(first.getAsJsonArray("probabilities").get(0).getAsDouble(), response.head("overall"), LOGIT_TOLERANCE);
            assertEquals(0.25, response.calibrationPrior(), 1.0E-12);
            assertEquals(1, client.health().acceptedCount());

            assertTrue(client.admit());
            var refused = client.infer(InferenceRequest.of(8, ModelKind.PRO, ModelWindow.CONTINUOUS,
                    features, bundle.sequenceLength(), 0, 1));
            assertThrows(Exception.class, () -> refused.get(5, TimeUnit.SECONDS));
            assertEquals(1, client.health().rejectedCount(), "a model that is not loaded is a refusal, not a flake");
        }
    }

    @Test void aTamperedWeightsFileIsRefused() throws Exception {
        Path copy = Files.createTempDirectory("aero-local-model");
        for (String name : new String[]{"manifest.json", "weights.json", "model.weights"}) {
            Files.copy(directory().resolve(name), copy.resolve(name));
        }
        byte[] weights = Files.readAllBytes(copy.resolve("model.weights"));
        weights[17] ^= 0x40;
        Files.write(copy.resolve("model.weights"), weights);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> LocalModelBundle.load(copy));
        assertTrue(error.getMessage().contains("does not match"), error.getMessage());
    }

    private static float[] floats(JsonArray array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsFloat();
        return values;
    }

    private static JsonObject cases() throws Exception {
        return JsonParser.parseString(Files.readString(directory().resolve("cases.json"))).getAsJsonObject();
    }

    private static Path directory() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        assertNotNull(root, "repository root not found");
        Path path = root.resolve("ml/tests/data/local_model");
        assertTrue(Files.isDirectory(path), "missing " + path + "; run python -m aeroml.tools.make_local_model_golden");
        return path;
    }
}
