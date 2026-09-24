package dev.aeroac.neural.inference;

/**
 * Detached payload: no AeroPlayer, entity, world or packet reference crosses onto the HTTP thread.
 * No username, UUID, pseudonym or server identifier is carried; requestId is a per-run counter only.
 */
public record InferenceRequest(long requestId, ModelKind model, ModelWindow window, int sequenceLength,
                               int featureCount, int featureSchemaVersion, float[] features,
                               long windowEndNanos, long collectorGeneration) {
    public static final int PROTOCOL_VERSION = 1;

    public InferenceRequest {
        if (features == null) throw new IllegalArgumentException("Missing features");
        if (sequenceLength < 1 || featureCount < 1) throw new IllegalArgumentException("Empty window");
        if ((long) sequenceLength * featureCount != features.length) {
            throw new IllegalArgumentException("Declared shape does not match payload");
        }
    }

    public static InferenceRequest of(long requestId, ModelKind model, ModelWindow window, float[] features,
                                      int sequenceLength, long windowEndNanos, long collectorGeneration) {
        return new InferenceRequest(requestId, model, window, sequenceLength, ModelFeature.FEATURE_COUNT,
                FeatureEncoder.FEATURE_SCHEMA_VERSION, features, windowEndNanos, collectorGeneration);
    }
}
