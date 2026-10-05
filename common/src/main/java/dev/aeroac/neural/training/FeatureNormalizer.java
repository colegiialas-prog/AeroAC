package dev.aeroac.neural.training;

import dev.aeroac.neural.inference.ModelFeature;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-channel normalisation fitted on the training fold only (ml/aeroml/dataset/normalize.py).
 * Statistics use known entries only; unknown entries stay exactly zero; mask channels pass through.
 */
public final class FeatureNormalizer {
    static final double MIN_STD = 1.0E-6;
    private static final int COUNT = ModelFeature.FEATURE_COUNT;

    final double[] mean = new double[COUNT];
    final double[] std = new double[COUNT];
    final long[] knownCounts = new long[COUNT];

    private FeatureNormalizer() {
        java.util.Arrays.fill(std, 1.0);
    }

    /** Fits on encoded (not yet normalised) windows, each row-major [t * COUNT + channel]. */
    public static FeatureNormalizer fit(List<float[]> windows) {
        if (windows.isEmpty()) throw new IllegalArgumentException("cannot fit normalisation on an empty training fold");
        FeatureNormalizer normalizer = new FeatureNormalizer();
        double[] sum = new double[COUNT];
        int[] maskOf = maskIndexes();
        for (float[] window : windows) {
            for (int base = 0; base < window.length; base += COUNT) {
                for (int channel = 0; channel < COUNT; channel++) {
                    boolean known = channel >= ModelFeature.VALUE_COUNT || maskOf[channel] < 0 || window[base + maskOf[channel]] > 0;
                    if (!known) continue;
                    normalizer.knownCounts[channel]++;
                    if (channel < ModelFeature.VALUE_COUNT) sum[channel] += window[base + channel];
                }
            }
        }
        for (int channel = 0; channel < ModelFeature.VALUE_COUNT; channel++) {
            if (normalizer.knownCounts[channel] > 0) normalizer.mean[channel] = sum[channel] / normalizer.knownCounts[channel];
        }
        double[] squares = new double[COUNT];
        for (float[] window : windows) {
            for (int base = 0; base < window.length; base += COUNT) {
                for (int channel = 0; channel < ModelFeature.VALUE_COUNT; channel++) {
                    if (maskOf[channel] >= 0 && !(window[base + maskOf[channel]] > 0)) continue;
                    double d = window[base + channel] - normalizer.mean[channel];
                    squares[channel] += d * d;
                }
            }
        }
        for (int channel = 0; channel < ModelFeature.VALUE_COUNT; channel++) {
            if (normalizer.knownCounts[channel] > 0) {
                normalizer.std[channel] = Math.max(Math.sqrt(squares[channel] / normalizer.knownCounts[channel]), MIN_STD);
            }
        }
        return normalizer;
    }

    /** Normalises in place, in float32 like the tensors PyTorch trains on. */
    public void apply(float[] window) {
        int[] maskOf = maskIndexes();
        for (int base = 0; base < window.length; base += COUNT) {
            for (int channel = 0; channel < ModelFeature.VALUE_COUNT; channel++) {
                double known = maskOf[channel] < 0 ? 1.0 : window[base + maskOf[channel]];
                window[base + channel] = (float) ((window[base + channel] - mean[channel]) / std[channel] * known);
            }
        }
    }

    /** Value channels a training fold never saw. */
    public List<String> neverObserved() {
        List<String> names = new ArrayList<>();
        for (ModelFeature feature : ModelFeature.VALUES) if (knownCounts[feature.ordinal()] == 0) names.add(feature.name());
        return names;
    }

    /** For each value channel, the index of its mask channel, or -1 when it is never unknown. */
    static int[] maskIndexes() {
        int[] result = new int[COUNT];
        java.util.Arrays.fill(result, -1);
        int mask = ModelFeature.VALUE_COUNT;
        for (ModelFeature feature : ModelFeature.VALUES) if (feature.nullable()) result[feature.ordinal()] = mask++;
        return result;
    }
}
