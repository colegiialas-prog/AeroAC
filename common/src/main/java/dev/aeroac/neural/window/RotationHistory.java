package dev.aeroac.neural.window;

import dev.aeroac.neural.target.AimErrorCalculator;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;

import java.util.Arrays;

/**
 * The last few rotation samples of a player who is not in combat yet.
 *
 * <p>Telemetry starts at a player's first attack, so without this the first attack of every fight
 * had no history and no window: exactly the moment an aimbot snaps onto its target went unseen.
 * This keeps a small primitive ring per player (no allocation per tick) and, when the collector
 * opens, turns it into ordinary frames that precede the attack sample.
 *
 * <p>Only what is known is written. The target is not known before the attack, so every target
 * field stays unknown rather than being filled from a guessed nearest entity. Rotation derivatives
 * follow the collector's warm-up exactly: deltas from the second sample, second differences and
 * acceleration from the third, jerk from the fourth.
 *
 * <p>Confined to the player's packet event loop, like the collector.
 */
public final class RotationHistory {
    /** A longer pause is a gap; history across it would join two unrelated moments. */
    public static final long MAX_GAP_NANOS = 150_000_000L;
    private static final int NANOS = 0, YAW = 1, PITCH = 2, GROUND = 3, SPRINT = 4, SNEAK = 5,
            GRID_YAW = 6, GRID_PITCH = 7, PING = 8, STRIDE = 9;

    private final int capacity;
    private final double[] data;
    private int start;
    private int size;

    public RotationHistory(int capacity) {
        if (capacity < 1 || capacity > 512) throw new IllegalArgumentException("capacity must be 1..512");
        this.capacity = capacity;
        this.data = new double[capacity * STRIDE];
    }

    public int capacity() { return capacity; }
    public int size() { return size; }
    public void clear() { start = size = 0; }

    /** One accepted movement tick. Non-finite rotation, or a gap since the last sample, restarts the history. */
    public void record(long nowNanos, double yaw, double pitch, boolean onGround, boolean sprinting, boolean sneaking,
                       double gridYaw, double gridPitch, double pingMs) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
            clear();
            return;
        }
        if (size > 0 && (nowNanos - lastNanos() > MAX_GAP_NANOS || nowNanos <= lastNanos())) clear();
        int slot;
        if (size < capacity) {
            slot = (start + size) % capacity;
            size++;
        } else {
            slot = start;
            start = (start + 1) % capacity;
        }
        int base = slot * STRIDE;
        data[base + NANOS] = nowNanos;
        data[base + YAW] = yaw;
        data[base + PITCH] = pitch;
        data[base + GROUND] = onGround ? 1 : 0;
        data[base + SPRINT] = sprinting ? 1 : 0;
        data[base + SNEAK] = sneaking ? 1 : 0;
        data[base + GRID_YAW] = gridYaw;
        data[base + GRID_PITCH] = gridPitch;
        data[base + PING] = pingMs;
    }

    private long lastNanos() { return (long) at(size - 1, NANOS); }

    private double at(int index, int field) { return data[((start + index) % capacity) * STRIDE + field]; }

    /**
     * The history as frames with ticks firstTick, firstTick + 1, ...; empty when there is none or
     * when its newest sample is already a gap away from now. The first frame starts a segment.
     */
    public CombatFrame[] frames(long firstTick, long nowNanos) {
        if (size == 0 || nowNanos - lastNanos() > MAX_GAP_NANOS) return new CombatFrame[0];
        CombatFrame[] frames = new CombatFrame[size];
        double[] values = new double[FrameField.COUNT];
        double previousYaw = 0, previousPitch = 0, previousDeltaYaw = 0, previousDeltaPitch = 0, previousAcceleration = 0;
        for (int i = 0; i < size; i++) {
            Arrays.fill(values, Double.NaN);
            double yaw = at(i, YAW), pitch = at(i, PITCH);
            long nanos = (long) at(i, NANOS);
            put(values, FrameField.YAW, yaw);
            put(values, FrameField.PITCH, pitch);
            double deltaYaw = AimErrorCalculator.normalizeYaw(yaw - previousYaw);
            double deltaPitch = pitch - previousPitch;
            double acceleration = Math.hypot(deltaYaw - previousDeltaYaw, deltaPitch - previousDeltaPitch);
            if (i >= 1) {
                put(values, FrameField.DELTA_YAW, deltaYaw);
                put(values, FrameField.DELTA_PITCH, deltaPitch);
                put(values, FrameField.ROTATION_SPEED, Math.hypot(deltaYaw, deltaPitch));
                put(values, FrameField.SAMPLE_INTERVAL_MS, (nanos - (long) at(i - 1, NANOS)) / 1_000_000.0);
            }
            if (i >= 2) {
                put(values, FrameField.DELTA2_YAW, deltaYaw - previousDeltaYaw);
                put(values, FrameField.DELTA2_PITCH, deltaPitch - previousDeltaPitch);
                put(values, FrameField.ROTATION_ACCELERATION, acceleration);
            }
            if (i >= 3) put(values, FrameField.ROTATION_JERK, acceleration - previousAcceleration);
            put(values, FrameField.ON_GROUND, at(i, GROUND));
            put(values, FrameField.AIRBORNE, 1 - at(i, GROUND));
            put(values, FrameField.SPRINTING, at(i, SPRINT));
            put(values, FrameField.SNEAKING, at(i, SNEAK));
            put(values, FrameField.MOUSE_GRID_YAW, at(i, GRID_YAW));
            put(values, FrameField.MOUSE_GRID_PITCH, at(i, GRID_PITCH));
            put(values, FrameField.PING_MS, at(i, PING));
            // Nothing is tracked yet: the same values the collector writes for "no target".
            put(values, FrameField.TARGET_PRESENT, 0);
            put(values, FrameField.TARGET_ENTITY_ID, -1);
            put(values, FrameField.PREVIOUS_TARGET_ENTITY_ID, -1);
            put(values, FrameField.TARGET_SWITCH, 0);
            put(values, FrameField.TICKS_SINCE_TARGET_SWITCH, -1);
            for (FrameField zero : new FrameField[]{FrameField.ATTACK, FrameField.SWING, FrameField.ATTACK_COUNT,
                    FrameField.SWING_COUNT, FrameField.CANCELLED_ATTACK_COUNT, FrameField.VEHICLE_STATE,
                    FrameField.TELEPORT_STATE, FrameField.REACH_EVIDENCE, FrameField.WALL_HIT_EVIDENCE,
                    FrameField.ENTITY_PIERCE_EVIDENCE, FrameField.PACKET_ORDER_EVIDENCE}) {
                put(values, zero, 0);
            }
            put(values, FrameField.SEGMENT_START, i == 0 ? 1 : 0);
            frames[i] = new CombatFrame(firstTick + i, nanos, values);
            if (i >= 1) {
                previousAcceleration = i >= 2 ? acceleration : 0;
                previousDeltaYaw = deltaYaw;
                previousDeltaPitch = deltaPitch;
            }
            previousYaw = yaw;
            previousPitch = pitch;
        }
        return frames;
    }

    private static void put(double[] values, FrameField field, double value) { values[field.ordinal()] = value; }
}
