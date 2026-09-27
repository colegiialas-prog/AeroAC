package dev.aeroac.neural.inference;

/** One accepted prediction, timestamped on arrival. Immutable; safe to publish across threads. */
public record PredictionResult(long requestId, long nanoTime, ModelKind model, String modelVersion, boolean calibrated,
                               String[] headNames, double[] headValues, long latencyMs, double calibrationPrior) {

    /** A result whose service did not report the base rate its calibration was fitted under. */
    public PredictionResult(long requestId, long nanoTime, ModelKind model, String modelVersion, boolean calibrated,
                            String[] headNames, double[] headValues, long latencyMs) {
        this(requestId, nanoTime, model, modelVersion, calibrated, headNames, headValues, latencyMs, Double.NaN);
    }

    public PredictionResult {
        if (headNames.length != headValues.length) throw new IllegalArgumentException("Head shape mismatch");
        headNames = headNames.clone();
        headValues = headValues.clone();
    }

    @Override public String[] headNames() { return headNames.clone(); }
    @Override public double[] headValues() { return headValues.clone(); }

    public static PredictionResult of(InferenceResponse response, long nanoTime, long latencyMs) {
        return new PredictionResult(response.requestId(), nanoTime, response.model(), response.modelVersion(),
                response.calibrated(), response.headNames(), response.headValues(), latencyMs,
                response.calibrationPrior());
    }

    /** Returns NaN for a head this model does not publish, never a fabricated zero. */
    public double head(String name) {
        for (int i = 0; i < headNames.length; i++) if (headNames[i].equals(name)) return headValues[i];
        return Double.NaN;
    }

    public double overall() { return head("overall"); }

    /** The calibration base rate when the service reported a usable one, otherwise NaN. */
    public boolean hasCalibrationPrior() { return calibrationPrior > 0 && calibrationPrior < 1; }

    public String describe() {
        StringBuilder text = new StringBuilder(model.wireName()).append(' ').append(modelVersion);
        if (!calibrated) text.append(" (uncalibrated)");
        for (int i = 0; i < headNames.length; i++) {
            text.append(' ').append(headNames[i]).append('=').append(String.format("%.3f", headValues[i]));
        }
        return text.append(" ").append(latencyMs).append("ms").toString();
    }
}
