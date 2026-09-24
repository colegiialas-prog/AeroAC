package dev.aeroac.neural.inference;

import dev.aeroac.neural.telemetry.FrameField;

/**
 * Ordered model input contract, mirrored by the newest ml/schema/feature_schema_v*.json.
 * Identity, absolute position and Grim check evidence are intentionally excluded:
 * the model must not be able to learn who a player is, nor to copy deterministic thresholds.
 */
public enum ModelFeature {
    DELTA_YAW(FrameField.DELTA_YAW, true, -180, 180),
    DELTA_PITCH(FrameField.DELTA_PITCH, true, -180, 180),
    DELTA2_YAW(FrameField.DELTA2_YAW, true, -360, 360),
    DELTA2_PITCH(FrameField.DELTA2_PITCH, true, -360, 360),
    ROTATION_SPEED(FrameField.ROTATION_SPEED, true, 0, 255),
    ROTATION_ACCELERATION(FrameField.ROTATION_ACCELERATION, true, 0, 512),
    ROTATION_JERK(FrameField.ROTATION_JERK, true, -512, 512),
    AIM_ERROR_YAW(FrameField.AIM_ERROR_YAW, true, -180, 180),
    AIM_ERROR_PITCH(FrameField.AIM_ERROR_PITCH, true, -180, 180),
    AIM_ERROR_TOTAL(FrameField.AIM_ERROR_TOTAL, true, 0, 255),
    AIM_ERROR_DELTA(FrameField.AIM_ERROR_DELTA, true, -255, 255),
    TARGET_ANGULAR_VELOCITY_YAW(null, true, -180, 180),
    TARGET_ANGULAR_VELOCITY_PITCH(null, true, -180, 180),
    ROTATION_TARGET_ALIGNMENT(null, true, -1, 1),
    DISTANCE_TO_TARGET(FrameField.DISTANCE_TO_TARGET, true, 0, 16),
    TARGET_RADIAL_SPEED(null, true, -8, 8),
    TARGET_SPEED(null, true, 0, 8),
    TARGET_ANGULAR_RADIUS(null, true, 0, 90),
    AIM_ERROR_RATIO(null, true, 0, 64),
    /** Fraction of the previous sample's aim error this rotation removed; a smoothing assist holds it near its k. */
    ROTATION_CORRECTION_GAIN(null, true, -3, 3),
    /** Rotation perpendicular to the previous aim error, same units; assisted rotation runs straight at the target. */
    ROTATION_OFF_AXIS(null, true, -3, 3),
    PLAYER_SPEED_HORIZONTAL(null, true, 0, 8),
    PLAYER_VELOCITY_Y(FrameField.VELOCITY_Y, true, -8, 8),
    ON_GROUND(FrameField.ON_GROUND, false, 0, 1),
    SPRINTING(FrameField.SPRINTING, false, 0, 1),
    SNEAKING(FrameField.SNEAKING, false, 0, 1),
    TARGET_PRESENT(FrameField.TARGET_PRESENT, false, 0, 1),
    ATTACK(FrameField.ATTACK, false, 0, 1),
    ATTACK_COUNT(FrameField.ATTACK_COUNT, false, 0, 8),
    SWING_COUNT(FrameField.SWING_COUNT, false, 0, 16),
    TARGET_SWITCH(FrameField.TARGET_SWITCH, false, 0, 1),
    TICKS_SINCE_ATTACK(FrameField.TICKS_SINCE_ATTACK, true, 0, 100),
    ATTACK_INTERVAL_MS(FrameField.ATTACK_INTERVAL_MS, true, 0, 2000),
    TICKS_SINCE_TARGET_SWITCH(FrameField.TICKS_SINCE_TARGET_SWITCH, true, 0, 12, Transform.LOG1P),
    PING_MS(FrameField.PING_MS, true, 0, 1000),
    ESTIMATED_JITTER_MS(FrameField.ESTIMATED_JITTER_MS, true, 0, 500),
    SAMPLE_INTERVAL_MS(FrameField.SAMPLE_INTERVAL_MS, true, 0, 500),
    SEGMENT_START(FrameField.SEGMENT_START, false, 0, 1);

    public static final ModelFeature[] VALUES = values();
    /** Number of value channels; mask channels for every nullable value follow them. */
    public static final int VALUE_COUNT = VALUES.length;
    public static final int MASK_COUNT = countNullable();
    public static final int FEATURE_COUNT = VALUE_COUNT + MASK_COUNT;

    private final FrameField field;
    private final boolean nullable;
    private final double low;
    private final double high;
    private final Transform transform;

    ModelFeature(FrameField field, boolean nullable, double low, double high) {
        this(field, nullable, low, high, Transform.NONE);
    }

    ModelFeature(FrameField field, boolean nullable, double low, double high, Transform transform) {
        this.field = field;
        this.nullable = nullable;
        this.low = low;
        this.high = high;
        this.transform = transform;
    }

    /**
     * Monotone per-channel transform, applied before clipping so the declared bounds are in
     * transformed units. Declared in the manifest and pinned by FeatureSchemaTest.
     */
    public enum Transform {
        NONE,
        /** log(1+x) for x >= 0. A negative input is unknown, not zero. */
        LOG1P;

        public double apply(double value) {
            if (this == NONE) return value;
            return value >= 0 ? Math.log1p(value) : Double.NaN;
        }

        public String wireName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public FrameField field() { return field; }
    public Transform transform() { return transform; }
    public boolean nullable() { return nullable; }
    public double low() { return low; }
    public double high() { return high; }
    public boolean derived() { return field == null; }

    /** Transform, then clip. A non-finite value stays unknown rather than becoming a clipped zero. */
    public double encode(double value) {
        return clip(transform.apply(value));
    }

    /** Clips into the declared range; a non-finite value stays unknown rather than becoming a clipped zero. */
    public double clip(double value) {
        if (!Double.isFinite(value)) return Double.NaN;
        return Math.max(low, Math.min(high, value));
    }

    private static int countNullable() {
        int count = 0;
        for (ModelFeature feature : VALUES) if (feature.nullable) count++;
        return count;
    }

    /** Channel order: every value, then one mask per nullable value in declaration order. */
    public static String[] channelNames() {
        String[] names = new String[FEATURE_COUNT];
        int mask = VALUE_COUNT;
        for (ModelFeature feature : VALUES) {
            names[feature.ordinal()] = feature.name();
            if (feature.nullable) names[mask++] = feature.name() + "_MASK";
        }
        return names;
    }
}
