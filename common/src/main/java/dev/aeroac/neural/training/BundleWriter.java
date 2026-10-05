package dev.aeroac.neural.training;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.inference.ModelFeature;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a model bundle the plugin's local mode and the Python tools both read: manifest.json
 * (ml/aeroml/export/bundle.py), weights.json and model.weights (ml/aeroml/export/java_weights.py).
 * No model.onnx: a Java-trained bundle runs in {@code mode: local}.
 */
final class BundleWriter {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

    private BundleWriter() { }

    static Map<String, Object> architecture(TrainableTcn model, double dropout) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("architecture", "temporal-convnet-v1");
        data.put("featureCount", model.features);
        data.put("sequenceLength", model.length);
        data.put("heads", model.heads);
        data.put("width", model.width);
        data.put("blocks", model.blocks);
        data.put("kernelSize", model.kernel);
        data.put("dropout", dropout);
        data.put("groups", model.groups);
        return data;
    }

    /** Writes weights.json and model.weights; returns {sha256, floatCount}. */
    static Map<String, Object> writeWeights(TrainableTcn model, double[] params, double dropout, Path directory) throws IOException {
        Files.createDirectories(directory);
        ByteBuffer buffer = ByteBuffer.allocate(params.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (double value : params) {
            float f = (float) value;
            if (!Float.isFinite(f)) throw new TrainingException("Обучение разошлось: в весах появились бесконечные значения.");
            buffer.putFloat(f);
        }
        byte[] blob = buffer.array();
        Files.write(directory.resolve("model.weights"), blob);
        List<Map<String, Object>> tensors = new ArrayList<>();
        for (TrainableTcn.Tensor tensor : model.tensors()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tensor.name());
            entry.put("shape", Arrays.stream(tensor.shape()).boxed().toList());
            entry.put("offset", tensor.offset());
            entry.put("count", tensor.count());
            tensors.add(entry);
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("weightsFormat", 1);
        index.put("architecture", architecture(model, dropout));
        index.put("floatCount", params.length);
        index.put("sha256", sha256(blob));
        index.put("tensors", tensors);
        Files.writeString(directory.resolve("weights.json"), JSON.toJson(index) + "\n", StandardCharsets.UTF_8);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sha256", index.get("sha256"));
        summary.put("floatCount", params.length);
        return summary;
    }

    static Map<String, Object> normalization(FeatureNormalizer normalizer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("featureSchemaVersion", FeatureEncoder.FEATURE_SCHEMA_VERSION);
        data.put("channels", List.of(ModelFeature.channelNames()));
        data.put("mean", Arrays.stream(normalizer.mean).boxed().toList());
        data.put("std", Arrays.stream(normalizer.std).boxed().toList());
        data.put("knownCounts", Arrays.stream(normalizer.knownCounts).boxed().toList());
        return data;
    }

    static void writeManifest(Path directory, String modelVersion, String kind, String window, int sequenceLength,
                              List<String> heads, FeatureNormalizer normalizer, Map<String, Object> calibration,
                              Map<String, Object> provenance) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleFormat", 1);
        manifest.put("modelVersion", modelVersion);
        manifest.put("modelKind", kind);
        manifest.put("window", window);
        manifest.put("sequenceLength", sequenceLength);
        manifest.put("featureSchemaVersion", FeatureEncoder.FEATURE_SCHEMA_VERSION);
        manifest.put("featureCount", ModelFeature.FEATURE_COUNT);
        manifest.put("channels", List.of(ModelFeature.channelNames()));
        manifest.put("heads", heads);
        manifest.put("normalization", normalization(normalizer));
        manifest.put("calibration", calibration);
        manifest.put("provenance", provenance);
        Files.writeString(directory.resolve("manifest.json"), JSON.toJson(manifest) + "\n", StandardCharsets.UTF_8);
    }

    static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, JSON.toJson(value) + "\n", StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
