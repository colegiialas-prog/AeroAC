package dev.aeroac.neural.training;

import dev.aeroac.neural.inference.ModelFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Yaw mirroring and channel dropout on normalised training windows (ml/aeroml/training/augment.py).
 * Training fold only; every reported number is measured on unmodified windows.
 */
final class WindowAugmenter {
    private static final List<String> YAW_ODD = List.of("DELTA_YAW", "DELTA2_YAW", "AIM_ERROR_YAW",
            "TARGET_ANGULAR_VELOCITY_YAW", "ROTATION_OFF_AXIS", "ROTATION_COUNTS_YAW");
    private static final int COUNT = ModelFeature.FEATURE_COUNT;

    private final double mirrorProbability;
    private final double channelDropout;
    private final int[] odd;
    private final int[] oddMask;
    private final float[] oddOffset;
    private final int[] nullableValue;
    private final int[] nullableMask;

    WindowAugmenter(FeatureNormalizer normalizer, double mirrorProbability, double channelDropout) {
        this.mirrorProbability = mirrorProbability;
        this.channelDropout = channelDropout;
        int[] maskOf = FeatureNormalizer.maskIndexes();
        List<Integer> oddList = new ArrayList<>();
        for (String name : YAW_ODD) {
            ModelFeature feature = ModelFeature.valueOf(name);
            if (feature.low() != -feature.high() || feature.transform() != ModelFeature.Transform.NONE) {
                throw new IllegalStateException(name + " is mirrored but its encoding is not symmetric about zero");
            }
            oddList.add(feature.ordinal());
        }
        odd = oddList.stream().mapToInt(Integer::intValue).toArray();
        oddMask = new int[odd.length];
        oddOffset = new float[odd.length];
        for (int i = 0; i < odd.length; i++) {
            oddMask[i] = maskOf[odd[i]];
            // Mirroring the raw value v -> -v in normalised space: (-v - m) / s = -x - 2m / s.
            oddOffset[i] = (float) (-2.0 * normalizer.mean[odd[i]] / normalizer.std[odd[i]]);
        }
        List<int[]> pairs = new ArrayList<>();
        for (ModelFeature feature : ModelFeature.VALUES) {
            if (feature.nullable()) pairs.add(new int[]{feature.ordinal(), maskOf[feature.ordinal()]});
        }
        nullableValue = pairs.stream().mapToInt(pair -> pair[0]).toArray();
        nullableMask = pairs.stream().mapToInt(pair -> pair[1]).toArray();
    }

    /** Augments a copy of {@code window}; the cached training window is never modified. */
    float[] apply(float[] window, SplittableRandom random) {
        float[] out = window.clone();
        if (mirrorProbability > 0 && random.nextDouble() < mirrorProbability) {
            for (int base = 0; base < out.length; base += COUNT) {
                for (int i = 0; i < odd.length; i++) {
                    float known = oddMask[i] < 0 ? 1f : out[base + oddMask[i]];
                    out[base + odd[i]] = (-out[base + odd[i]] + oddOffset[i]) * known;
                }
            }
        }
        if (channelDropout > 0) {
            for (int i = 0; i < nullableValue.length; i++) {
                if (random.nextDouble() >= channelDropout) continue;
                for (int base = 0; base < out.length; base += COUNT) {
                    out[base + nullableValue[i]] = 0f;
                    out[base + nullableMask[i]] = 0f;
                }
            }
        }
        return out;
    }
}
