package dev.aeroac.neural.inference.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.inference.ModelFeature;
import dev.aeroac.neural.inference.ModelKind;
import dev.aeroac.neural.inference.ModelWindow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A model bundle directory (manifest.json, weights.json, model.weights) loaded for in-JVM inference.
 * Mirrors ml/aeroml/service/runtime.py LoadedModel: the same schema refusal, the same normalisation
 * (mask channels pass through, unknown values stay zero) and the same per-head calibration. A bundle
 * that does not match this build's feature schema is refused at load, never reinterpreted.
 */
public final class LocalModelBundle {
    private static final int BUNDLE_FORMAT = 1;
    private static final int WEIGHTS_FORMAT = 1;
    private static final long MAX_WEIGHTS_BYTES = 256L * 1024 * 1024;

    private final String modelVersion;
    private final ModelKind kind;
    private final ModelWindow window;
    private final int sequenceLength;
    private final TemporalConvNet network;
    private final double[] mean;
    private final double[] std;
    private final Calibration[] calibration;
    private final double calibrationPrior;

    /** sigmoid(slope * logit + bias); temperature scaling is slope = 1/T, bias = 0. */
    private record Calibration(double slope, double bias) {
        double apply(double logit) { return 1.0 / (1.0 + Math.exp(-(slope * logit + bias))); }
    }

    private LocalModelBundle(String modelVersion, ModelKind kind, ModelWindow window, int sequenceLength,
                             TemporalConvNet network, double[] mean, double[] std, Calibration[] calibration,
                             double calibrationPrior) {
        this.modelVersion = modelVersion;
        this.kind = kind;
        this.window = window;
        this.sequenceLength = sequenceLength;
        this.network = network;
        this.mean = mean;
        this.std = std;
        this.calibration = calibration;
        this.calibrationPrior = calibrationPrior;
    }

    public String modelVersion() { return modelVersion; }
    public ModelKind kind() { return kind; }
    public ModelWindow window() { return window; }
    public int sequenceLength() { return sequenceLength; }
    public boolean calibrated() { return calibration != null; }
    public List<String> heads() { return network.heads(); }
    /** The calibration fold's base rate for the overall head, or NaN when the bundle has none. */
    public double calibrationPrior() { return calibrationPrior; }

    /** Calibrated probabilities in {@link #heads()} order, clipped to [0,1] as the service does. */
    public double[] predict(float[] features) {
        float[] normalised = normalise(features);
        double[] logits = network.logits(normalised);
        double[] probabilities = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            double value = calibration == null ? 1.0 / (1.0 + Math.exp(-logits[i])) : calibration[i].apply(logits[i]);
            if (!Double.isFinite(value)) throw new IllegalStateException("model produced a non-finite score");
            probabilities[i] = Math.max(0, Math.min(1, value));
        }
        return probabilities;
    }

    /** Raw logits, before calibration; exposed for the cross-language golden test. */
    double[] logits(float[] features) { return network.logits(normalise(features)); }

    private float[] normalise(float[] features) {
        int count = ModelFeature.FEATURE_COUNT;
        if (features.length != sequenceLength * count) {
            throw new IllegalArgumentException("window holds " + features.length + " values, bundle expects "
                    + sequenceLength + " x " + count);
        }
        float[] out = new float[features.length];
        for (int t = 0; t < sequenceLength; t++) {
            int base = t * count;
            int mask = base + ModelFeature.VALUE_COUNT;
            for (ModelFeature feature : ModelFeature.VALUES) {
                int channel = base + feature.ordinal();
                double known = feature.nullable() ? features[mask++] : 1.0;
                out[channel] = (float) ((features[channel] - mean[feature.ordinal()]) / std[feature.ordinal()] * known);
            }
            for (int channel = ModelFeature.VALUE_COUNT; channel < count; channel++) out[base + channel] = features[base + channel];
        }
        return out;
    }

    public static LocalModelBundle load(Path directory) throws IOException {
        JsonObject manifest = json(directory.resolve("manifest.json"));
        if (integer(manifest, "bundleFormat") != BUNDLE_FORMAT) throw invalid("unsupported bundle format");
        int schema = integer(manifest, "featureSchemaVersion");
        if (schema != FeatureEncoder.FEATURE_SCHEMA_VERSION) {
            throw invalid("bundle was built for feature schema " + schema + ", this build encodes "
                    + FeatureEncoder.FEATURE_SCHEMA_VERSION + "; retrain or use a matching bundle");
        }
        String[] channels = strings(manifest.getAsJsonArray("channels"));
        if (!Arrays.equals(channels, ModelFeature.channelNames())) throw invalid("bundle channels differ from this build");
        List<String> heads = List.of(strings(manifest.getAsJsonArray("heads")));
        if (!heads.contains("overall")) throw invalid("bundle does not publish an overall head");
        String modelVersion = manifest.get("modelVersion").getAsString();
        if (modelVersion.isBlank() || modelVersion.length() > 128) throw invalid("invalid modelVersion");
        ModelKind kind = ModelKind.valueOf(text(manifest, "modelKind", "flash").toUpperCase(java.util.Locale.ROOT));
        ModelWindow window = ModelWindow.parse(text(manifest, "window", "attack"));
        int sequenceLength = integer(manifest, "sequenceLength");
        if (sequenceLength < 1 || sequenceLength > 512) throw invalid("sequenceLength outside 1..512");

        JsonObject normalization = manifest.getAsJsonObject("normalization");
        double[] mean = doubles(normalization.getAsJsonArray("mean"));
        double[] std = doubles(normalization.getAsJsonArray("std"));
        if (mean.length != channels.length || std.length != channels.length) throw invalid("normalisation length");
        for (int i = 0; i < std.length; i++) {
            if (!Double.isFinite(mean[i]) || !Double.isFinite(std[i]) || std[i] <= 0) throw invalid("normalisation values");
        }

        Calibration[] calibration = null;
        double prior = Double.NaN;
        JsonElement calibrationElement = manifest.get("calibration");
        if (calibrationElement != null && calibrationElement.isJsonObject()) {
            JsonObject data = calibrationElement.getAsJsonObject();
            calibration = new Calibration[heads.size()];
            String method = text(data, "method", "");
            if (method.equals("per-head") || method.equals("per-head-temperature")) {
                JsonObject scalers = data.getAsJsonObject("scalers");
                for (int i = 0; i < heads.size(); i++) {
                    JsonElement scaler = scalers.get(heads.get(i));
                    if (scaler == null) throw invalid("calibration misses head " + heads.get(i));
                    calibration[i] = scaler(scaler.getAsJsonObject());
                }
                JsonElement priors = data.get("priors");
                if (priors != null && priors.isJsonObject() && priors.getAsJsonObject().has("overall")) {
                    double value = priors.getAsJsonObject().get("overall").getAsDouble();
                    if (value > 0 && value < 1) prior = value;
                }
            } else {
                Arrays.fill(calibration, scaler(data));
            }
        }

        JsonObject index = json(directory.resolve("weights.json"));
        if (integer(index, "weightsFormat") != WEIGHTS_FORMAT) throw invalid("unsupported weights format");
        JsonObject architecture = index.getAsJsonObject("architecture");
        if (!"temporal-convnet-v1".equals(text(architecture, "architecture", ""))) throw invalid("unsupported architecture");
        if (integer(architecture, "featureCount") != channels.length || integer(architecture, "sequenceLength") != sequenceLength
                || !List.of(strings(architecture.getAsJsonArray("heads"))).equals(heads)) {
            throw invalid("weights describe a different model than the manifest");
        }
        Path weightsPath = directory.resolve("model.weights");
        if (Files.size(weightsPath) > MAX_WEIGHTS_BYTES) throw invalid("model.weights is implausibly large");
        byte[] blob = Files.readAllBytes(weightsPath);
        if (!sha256(blob).equals(index.get("sha256").getAsString())) throw invalid("model.weights does not match its index");
        long floats = index.get("floatCount").getAsLong();
        if (floats * 4 != blob.length) throw invalid("model.weights has the wrong length");
        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, float[]> tensors = new HashMap<>();
        Map<String, int[]> shapes = new HashMap<>();
        for (JsonElement element : index.getAsJsonArray("tensors")) {
            JsonObject tensor = element.getAsJsonObject();
            String name = tensor.get("name").getAsString();
            int offset = tensor.get("offset").getAsInt();
            int count = tensor.get("count").getAsInt();
            int[] shape = ints(tensor.getAsJsonArray("shape"));
            if (offset < 0 || count < 0 || (long) offset + count > floats || product(shape) != count) {
                throw invalid("tensor " + name + " is out of bounds");
            }
            float[] values = new float[count];
            for (int i = 0; i < count; i++) {
                values[i] = buffer.getFloat((offset + i) * 4);
                if (!Float.isFinite(values[i])) throw invalid("tensor " + name + " holds a non-finite weight");
            }
            tensors.put(name, values);
            shapes.put(name, shape);
        }
        TemporalConvNet network = new TemporalConvNet(channels.length, sequenceLength, integer(architecture, "width"),
                integer(architecture, "blocks"), integer(architecture, "kernelSize"), integer(architecture, "groups"),
                heads, tensors, shapes);
        return new LocalModelBundle(modelVersion, kind, window, sequenceLength, network, mean, std, calibration, prior);
    }

    private static Calibration scaler(JsonObject data) {
        String method = text(data, "method", "");
        if (method.equals("platt")) {
            double slope = data.get("slope").getAsDouble(), bias = data.get("bias").getAsDouble();
            if (!(slope > 0) || !Double.isFinite(slope) || !Double.isFinite(bias)) throw invalid("platt parameters");
            return new Calibration(slope, bias);
        }
        if (method.equals("temperature")) {
            double temperature = data.get("temperature").getAsDouble();
            if (!(temperature > 0) || !Double.isFinite(temperature)) throw invalid("temperature");
            return new Calibration(1.0 / temperature, 0);
        }
        throw invalid("unsupported calibration method " + method);
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static int integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) throw invalid("missing " + key);
        return value.getAsInt();
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static String[] strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) values.add(element.getAsString());
        return values.toArray(new String[0]);
    }

    private static double[] doubles(JsonArray array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsDouble();
        return values;
    }

    private static int[] ints(JsonArray array) {
        int[] values = new int[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsInt();
        return values;
    }

    private static long product(int[] shape) {
        long product = 1;
        for (int dimension : shape) {
            if (dimension < 0) return -1;
            product *= dimension;
        }
        return product;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
