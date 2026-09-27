package dev.aeroac.neural.inference;

import dev.aeroac.neural.target.AimErrorCalculator;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;

/**
 * Turns an immutable frame window into the flat [T][F] float payload the model consumes.
 * Derived values are window-local: index 0 has no predecessor inside the window, so its
 * derived deltas are unknown. ml/aeroml/features.py applies exactly the same rule.
 */
public final class FeatureEncoder {
    public static final int FEATURE_SCHEMA_VERSION = 4;
    private static final double EPSILON = 1.0E-6;
    /** Below this aim error (degrees) the error-frame channels are noise and stay unknown. */
    private static final double MIN_ERROR_FRAME_DEGREES = 1.0;

    private FeatureEncoder() { }

    /** Row-major [t * FEATURE_COUNT + channel]. Unknown values encode as 0 with a 0 mask. */
    public static float[] encode(CombatFrame[] window) {
        if (window == null || window.length == 0) throw new IllegalArgumentException("Empty window");
        float[] output = new float[window.length * ModelFeature.FEATURE_COUNT];
        for (int t = 0; t < window.length; t++) {
            CombatFrame frame = window[t];
            if (frame == null) throw new IllegalArgumentException("Window hole at " + t);
            CombatFrame previous = t == 0 ? null : window[t - 1];
            boolean continuous = sameTarget(frame, previous);
            int base = t * ModelFeature.FEATURE_COUNT;
            int mask = base + ModelFeature.VALUE_COUNT;
            for (ModelFeature feature : ModelFeature.VALUES) {
                double raw = feature.derived() ? derive(feature, frame, previous, continuous) : frame.value(feature.field());
                if (feature == ModelFeature.TICKS_SINCE_TARGET_SWITCH && raw < 0) raw = Double.NaN;
                double clipped = feature.encode(raw);
                boolean known = Double.isFinite(clipped);
                output[base + feature.ordinal()] = known ? (float) clipped : 0f;
                if (feature.nullable()) output[mask++] = known ? 1f : 0f;
            }
        }
        return output;
    }

    /** Both samples must describe the same tracked entity inside one uninterrupted segment. */
    private static boolean sameTarget(CombatFrame frame, CombatFrame previous) {
        return previous != null
                && frame.value(FrameField.TARGET_PRESENT) == 1
                && previous.value(FrameField.TARGET_PRESENT) == 1
                && frame.value(FrameField.TARGET_ENTITY_ID) == previous.value(FrameField.TARGET_ENTITY_ID)
                && frame.value(FrameField.TARGET_SWITCH) == 0
                && frame.value(FrameField.SEGMENT_START) == 0;
    }

    /**
     * {on target, entry height, degrees to box centre} for one sample, NaN where unknown. A slab
     * test of the look ray against the one target box already in the frame; not a world raycast.
     * Same order of operations as crosshair() in ml/aeroml/dataset/features.py.
     */
    static double[] crosshair(CombatFrame frame) {
        double[] result = {Double.NaN, Double.NaN, Double.NaN};
        if (frame.value(FrameField.TARGET_PRESENT) != 1) return result;
        double yaw = Math.toRadians(frame.value(FrameField.YAW)), pitch = Math.toRadians(frame.value(FrameField.PITCH));
        double[] look = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] eye = {frame.value(FrameField.PLAYER_X),
                frame.value(FrameField.PLAYER_Y) + frame.value(FrameField.EYE_HEIGHT), frame.value(FrameField.PLAYER_Z)};
        double[] low = {frame.value(FrameField.TARGET_MIN_X), frame.value(FrameField.TARGET_MIN_Y), frame.value(FrameField.TARGET_MIN_Z)};
        double[] high = {frame.value(FrameField.TARGET_MAX_X), frame.value(FrameField.TARGET_MAX_Y), frame.value(FrameField.TARGET_MAX_Z)};
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(look[axis]) || !Double.isFinite(eye[axis])
                    || !Double.isFinite(low[axis]) || !Double.isFinite(high[axis])) return result;
        }
        double near = Double.NEGATIVE_INFINITY, far = Double.POSITIVE_INFINITY;
        boolean hit = true;
        for (int axis = 0; axis < 3 && hit; axis++) {
            double o = eye[axis], d = look[axis];
            if (Math.abs(d) < 1.0E-9) {
                if (o < low[axis] || o > high[axis]) hit = false;
                continue;
            }
            double first = (low[axis] - o) / d, second = (high[axis] - o) / d;
            if (first > second) { double swap = first; first = second; second = swap; }
            near = Math.max(near, first);
            far = Math.min(far, second);
        }
        double entry = Math.max(near, 0.0);
        hit = hit && entry <= far;
        result[0] = hit ? 1 : 0;
        double height = high[1] - low[1];
        if (hit && height > EPSILON) result[1] = (eye[1] + entry * look[1] - low[1]) / height;
        double cx = 0.5 * (low[0] + high[0]) - eye[0], cy = 0.5 * (low[1] + high[1]) - eye[1], cz = 0.5 * (low[2] + high[2]) - eye[2];
        double norm = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (norm >= EPSILON) {
            double cosine = (look[0] * cx + look[1] * cy + look[2] * cz) / norm;
            result[2] = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosine))));
        }
        return result;
    }

    private static double derive(ModelFeature feature, CombatFrame frame, CombatFrame previous, boolean continuous) {
        switch (feature) {
            case TARGET_ANGULAR_VELOCITY_YAW:
                return continuous ? AimErrorCalculator.normalizeYaw(
                        frame.value(FrameField.TARGET_YAW) - previous.value(FrameField.TARGET_YAW)) : Double.NaN;
            case TARGET_ANGULAR_VELOCITY_PITCH:
                return continuous ? frame.value(FrameField.TARGET_PITCH) - previous.value(FrameField.TARGET_PITCH) : Double.NaN;
            case ROTATION_TARGET_ALIGNMENT: {
                if (!continuous) return Double.NaN;
                double ty = AimErrorCalculator.normalizeYaw(frame.value(FrameField.TARGET_YAW) - previous.value(FrameField.TARGET_YAW));
                double tp = frame.value(FrameField.TARGET_PITCH) - previous.value(FrameField.TARGET_PITCH);
                double py = frame.value(FrameField.DELTA_YAW), pp = frame.value(FrameField.DELTA_PITCH);
                double targetNorm = Math.hypot(ty, tp), playerNorm = Math.hypot(py, pp);
                if (!(targetNorm > EPSILON) || !(playerNorm > EPSILON)) return Double.NaN;
                return (py * ty + pp * tp) / (targetNorm * playerNorm);
            }
            case TARGET_RADIAL_SPEED:
                return continuous ? frame.value(FrameField.DISTANCE_TO_TARGET) - previous.value(FrameField.DISTANCE_TO_TARGET) : Double.NaN;
            case TARGET_SPEED: {
                double x = frame.value(FrameField.TARGET_VELOCITY_X);
                double y = frame.value(FrameField.TARGET_VELOCITY_Y);
                double z = frame.value(FrameField.TARGET_VELOCITY_Z);
                return Math.sqrt(x * x + y * y + z * z);
            }
            case TARGET_ANGULAR_RADIUS: {
                double distance = frame.value(FrameField.DISTANCE_TO_TARGET);
                if (!(distance > EPSILON)) return Double.NaN;
                double width = Math.max(frame.value(FrameField.TARGET_MAX_X) - frame.value(FrameField.TARGET_MIN_X),
                        frame.value(FrameField.TARGET_MAX_Z) - frame.value(FrameField.TARGET_MIN_Z));
                return Math.toDegrees(Math.atan2(0.5 * width, distance));
            }
            case AIM_ERROR_RATIO: {
                double radius = derive(ModelFeature.TARGET_ANGULAR_RADIUS, frame, previous, continuous);
                if (!(radius > EPSILON)) return Double.NaN;
                return frame.value(FrameField.AIM_ERROR_TOTAL) / radius;
            }
            case ROTATION_CORRECTION_GAIN:
            case ROTATION_OFF_AXIS: {
                // This sample's rotation in the frame of the aim error it responded to (the previous
                // sample's). Same order of operations as ml/aeroml/dataset/features.py.
                if (!continuous) return Double.NaN;
                double errorYaw = AimErrorCalculator.normalizeYaw(previous.value(FrameField.YAW) - previous.value(FrameField.TARGET_YAW));
                double errorPitch = previous.value(FrameField.PITCH) - previous.value(FrameField.TARGET_PITCH);
                double norm2 = errorYaw * errorYaw + errorPitch * errorPitch;
                if (!(norm2 >= MIN_ERROR_FRAME_DEGREES * MIN_ERROR_FRAME_DEGREES)) return Double.NaN;
                double deltaYaw = frame.value(FrameField.DELTA_YAW), deltaPitch = frame.value(FrameField.DELTA_PITCH);
                return feature == ModelFeature.ROTATION_CORRECTION_GAIN
                        ? -(deltaYaw * errorYaw + deltaPitch * errorPitch) / norm2
                        : (deltaYaw * errorPitch - deltaPitch * errorYaw) / norm2;
            }
            case CROSSHAIR_ON_TARGET:
                return crosshair(frame)[0];
            case CROSSHAIR_HIT_HEIGHT:
                return crosshair(frame)[1];
            case CENTER_AIM_ERROR:
                return crosshair(frame)[2];
            case CENTER_AIM_ERROR_RATIO: {
                double radius = derive(ModelFeature.TARGET_ANGULAR_RADIUS, frame, previous, continuous);
                if (!(radius > EPSILON)) return Double.NaN;
                return crosshair(frame)[2] / radius;
            }
            case PLAYER_SPEED_HORIZONTAL: {
                double x = frame.value(FrameField.VELOCITY_X), z = frame.value(FrameField.VELOCITY_Z);
                return Math.sqrt(x * x + z * z);
            }
            default:
                throw new IllegalStateException("Undeclared derivation " + feature);
        }
    }
}
