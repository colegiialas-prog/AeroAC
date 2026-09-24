package dev.aeroac.neural.inference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * Wire format for the first inference protocol. Deliberately explicit about versions: the service
 * must refuse an incompatible schema instead of reinterpreting the feature order it was handed.
 * A binary or gRPC transport can replace this without touching the collector.
 */
public final class InferenceJson {
    private static final int MAX_HEADS = 16;
    private static final int MAX_MODEL_VERSION = 128;
    private static final int MAX_HEAD_NAME = 64;

    private InferenceJson() { }

    /** Built off the packet thread. Features are row-major [t][channel], flattened. */
    public static String encode(InferenceRequest request) {
        float[] features = request.features();
        StringBuilder body = new StringBuilder(features.length * 8 + 256);
        body.append("{\"protocolVersion\":").append(InferenceRequest.PROTOCOL_VERSION)
                .append(",\"featureSchemaVersion\":").append(request.featureSchemaVersion())
                .append(",\"requestId\":").append(request.requestId())
                .append(",\"model\":\"").append(request.model().wireName())
                .append("\",\"window\":\"").append(request.window().wireName())
                .append("\",\"sequenceLength\":").append(request.sequenceLength())
                .append(",\"featureCount\":").append(request.featureCount())
                .append(",\"features\":[");
        for (int i = 0; i < features.length; i++) {
            float value = features[i];
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite feature at " + i);
            if (i > 0) body.append(',');
            body.append(value);
        }
        return body.append("]}").toString();
    }

    /** Strict: a malformed or mismatched answer is a service fault, never a fact about the player. */
    public static InferenceResponse decode(String body, InferenceRequest request) {
        JsonElement parsed = new JsonParser().parse(body);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Response is not an object");
        JsonObject json = parsed.getAsJsonObject();
        int protocol = integer(json, "protocolVersion");
        int schema = integer(json, "featureSchemaVersion");
        if (protocol != InferenceRequest.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Incompatible protocolVersion " + protocol);
        }
        if (schema != request.featureSchemaVersion()) {
            throw new IllegalArgumentException("Incompatible featureSchemaVersion " + schema);
        }
        long requestId = wholeNumber(json, "requestId");
        if (requestId != request.requestId()) throw new IllegalArgumentException("Response for another request");
        if (json.has("model") && !request.model().wireName().equals(text(json, "model", 16))) {
            throw new IllegalArgumentException("Response for another model");
        }
        String modelVersion = text(json, "modelVersion", MAX_MODEL_VERSION);
        JsonElement headsElement = json.get("heads");
        if (headsElement == null || !headsElement.isJsonObject()) throw new IllegalArgumentException("Missing heads");
        JsonObject heads = headsElement.getAsJsonObject();
        if (heads.entrySet().isEmpty() || heads.entrySet().size() > MAX_HEADS) {
            throw new IllegalArgumentException("Head count " + heads.entrySet().size() + " outside 1.." + MAX_HEADS);
        }
        String[] names = new String[heads.entrySet().size()];
        double[] values = new double[names.length];
        int index = 0;
        for (Map.Entry<String, JsonElement> head : heads.entrySet()) {
            String name = head.getKey();
            if (name == null || name.isBlank() || name.length() > MAX_HEAD_NAME) {
                throw new IllegalArgumentException("Malformed head name");
            }
            JsonElement value = head.getValue();
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("Non-numeric head " + name);
            }
            double probability = value.getAsDouble();
            if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
                throw new IllegalArgumentException("Head " + name + " outside [0,1]");
            }
            names[index] = name;
            values[index++] = probability;
        }
        JsonElement calibration = json.get("calibrated");
        if (calibration != null && (!calibration.isJsonPrimitive() || !calibration.getAsJsonPrimitive().isBoolean())) {
            throw new IllegalArgumentException("calibrated must be boolean");
        }
        boolean calibrated = calibration != null && calibration.getAsBoolean();
        return new InferenceResponse(requestId, protocol, schema, modelVersion, request.model(), calibrated, names, values);
    }

    private static int integer(JsonObject json, String key) {
        return Math.toIntExact(wholeNumber(json, key));
    }

    private static long wholeNumber(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException malformed) {
            throw new IllegalArgumentException("Expected integer " + key, malformed);
        }
    }

    private static String text(JsonObject json, String key, int max) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        String result = value.getAsString();
        if (result.isBlank() || result.length() > max) throw new IllegalArgumentException("Malformed " + key);
        return result;
    }
}
