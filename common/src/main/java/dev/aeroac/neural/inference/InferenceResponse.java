package dev.aeroac.neural.inference;

/**
 * Decoded service answer. Heads are name/value pairs so a model can add a head without any change
 * to the Java telemetry protocol; unknown head names are carried through to operators untouched.
 */
public record InferenceResponse(long requestId, int protocolVersion, int featureSchemaVersion, String modelVersion,
                                ModelKind model, boolean calibrated, String[] headNames, double[] headValues) {

    public InferenceResponse {
        if (headNames == null || headValues == null || headNames.length != headValues.length) {
            throw new IllegalArgumentException("Malformed heads");
        }
        if (modelVersion == null || modelVersion.isBlank()) throw new IllegalArgumentException("Missing modelVersion");
        for (int i = 0; i < headValues.length; i++) {
            if (headNames[i] == null || headNames[i].isBlank()) throw new IllegalArgumentException("Unnamed head");
            if (!(headValues[i] >= 0) || !(headValues[i] <= 1)) {
                throw new IllegalArgumentException("Head " + headNames[i] + " outside [0,1]");
            }
        }
        if (head(headNames, headValues, "overall") < 0) throw new IllegalArgumentException("Missing overall head");
    }

    public double head(String name) { return head(headNames, headValues, name); }

    private static double head(String[] names, double[] values, String name) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return values[i];
        return -1;
    }
}
