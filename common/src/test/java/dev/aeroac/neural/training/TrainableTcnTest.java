package dev.aeroac.neural.training;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aeroac.neural.inference.ModelFeature;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The trainable network must compute what PyTorch computes (forward, checked against the logits
 * PyTorch wrote into ml/tests/data/local_model) and its hand-written backward pass must be the
 * derivative of that forward (checked against central differences).
 */
class TrainableTcnTest {

    @Test void forwardReproducesPyTorchLogitsOnTheGoldenBundle() throws Exception {
        Path directory = golden();
        JsonObject index = JsonParser.parseString(Files.readString(directory.resolve("weights.json"))).getAsJsonObject();
        JsonObject architecture = index.getAsJsonObject("architecture");
        List<String> heads = List.of("overall", "aimAssist");
        TrainableTcn model = new TrainableTcn(architecture.get("featureCount").getAsInt(),
                architecture.get("sequenceLength").getAsInt(), architecture.get("width").getAsInt(),
                architecture.get("blocks").getAsInt(), architecture.get("kernelSize").getAsInt(),
                architecture.get("groups").getAsInt(), 0.1, heads, 1);
        ByteBuffer blob = ByteBuffer.wrap(Files.readAllBytes(directory.resolve("model.weights"))).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(model.parameterCount(), index.get("floatCount").getAsInt(), "same parameter count as PyTorch");
        int position = 0;
        for (JsonElement element : index.getAsJsonArray("tensors")) {
            JsonObject tensor = element.getAsJsonObject();
            TrainableTcn.Tensor mine = model.tensors().get(position++);
            assertEquals(tensor.get("name").getAsString(), mine.name(), "state_dict order");
            assertEquals(tensor.get("offset").getAsInt(), mine.offset());
            for (int i = 0; i < mine.count(); i++) {
                model.params[mine.offset() + i] = blob.getFloat((tensor.get("offset").getAsInt() + i) * 4);
            }
        }
        // The fixture's cases are raw encoded windows; normalise them as LocalModelBundle does.
        JsonObject manifest = JsonParser.parseString(Files.readString(directory.resolve("manifest.json"))).getAsJsonObject();
        JsonObject normalization = manifest.getAsJsonObject("normalization");
        FeatureNormalizer normalizer = FeatureNormalizer.fit(List.of(new float[ModelFeature.FEATURE_COUNT]));
        for (int i = 0; i < ModelFeature.FEATURE_COUNT; i++) {
            normalizer.mean[i] = normalization.getAsJsonArray("mean").get(i).getAsDouble();
            normalizer.std[i] = normalization.getAsJsonArray("std").get(i).getAsDouble();
        }
        JsonObject cases = JsonParser.parseString(Files.readString(directory.resolve("cases.json"))).getAsJsonObject();
        TrainableTcn.Workspace space = model.workspace();
        int checked = 0;
        for (JsonElement element : cases.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            var raw = item.getAsJsonArray("features");
            float[] window = new float[raw.size()];
            for (int i = 0; i < window.length; i++) window[i] = raw.get(i).getAsFloat();
            normalizer.apply(window);
            double[] logits = model.forward(space, window, null);
            var expected = item.getAsJsonArray("logits");
            for (int h = 0; h < logits.length; h++) assertEquals(expected.get(h).getAsDouble(), logits[h], 1e-4);
            checked++;
        }
        assertTrue(checked > 0);
    }

    @Test void backwardMatchesCentralDifferences() {
        TrainableTcn model = new TrainableTcn(6, 9, 8, 2, 3, 4, 0.0, List.of("overall", "aimAssist"), 7);
        SplittableRandom random = new SplittableRandom(3);
        // Push the GroupNorm affine parameters off their identity init so their gradients are exercised.
        for (int i = 0; i < model.params.length; i++) model.params[i] += (random.nextDouble() - 0.5) * 0.2;
        float[] window = new float[9 * 6];
        for (int i = 0; i < window.length; i++) window[i] = (float) (random.nextGaussian());
        double[] dLogits = {0.7, -1.3};
        TrainableTcn.Workspace space = model.workspace();
        model.forward(space, window, null);
        double[] grad = new double[model.parameterCount()];
        model.backward(space, dLogits, grad);
        double epsilon = 1e-5;
        double worst = 0;
        for (int i = 0; i < model.params.length; i++) {
            double saved = model.params[i];
            model.params[i] = saved + epsilon;
            double up = objective(model, space, window, dLogits);
            model.params[i] = saved - epsilon;
            double down = objective(model, space, window, dLogits);
            model.params[i] = saved;
            double numeric = (up - down) / (2 * epsilon);
            double error = Math.abs(numeric - grad[i]) / Math.max(1e-3, Math.abs(numeric) + Math.abs(grad[i]));
            worst = Math.max(worst, error);
        }
        assertTrue(worst < 1e-4, "largest relative gradient error " + worst);
    }

    private static double objective(TrainableTcn model, TrainableTcn.Workspace space, float[] window, double[] weights) {
        double[] logits = model.forward(space, window, null);
        double total = 0;
        for (int h = 0; h < logits.length; h++) total += weights[h] * logits[h];
        return total;
    }

    @Test void initialisationFollowsPyTorchDefaults() {
        TrainableTcn model = new TrainableTcn(ModelFeature.FEATURE_COUNT, 31, 64, 4, 3, 8, 0.1, List.of("overall", "aimAssist"), 0);
        assertEquals(113_795, model.parameterCount(), "what PyTorch reports for Flash on feature schema v5");
        assertEquals(61, model.receptiveField());
        for (TrainableTcn.Tensor tensor : model.tensors()) {
            if (!tensor.name().contains("norm") && !tensor.name().startsWith("project.1")) continue;
            double expected = tensor.name().endsWith("weight") ? 1.0 : 0.0;
            for (int i = 0; i < tensor.count(); i++) assertEquals(expected, model.params[tensor.offset() + i], tensor.name());
        }
    }

    static Path golden() {
        Path direct = Path.of("../ml/tests/data/local_model");
        return Files.isDirectory(direct) ? direct : Path.of("ml/tests/data/local_model");
    }
}
