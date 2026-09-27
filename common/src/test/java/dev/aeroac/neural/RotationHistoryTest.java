package dev.aeroac.neural;

import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.inference.ModelFeature;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;
import dev.aeroac.neural.window.AttackWindowBuilder;
import dev.aeroac.neural.window.RotationHistory;
import dev.aeroac.neural.window.TemporalRingBuffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RotationHistoryTest {
    private static final long TICK = 50_000_000L;

    private static RotationHistory filled(int capacity, double... yaws) {
        RotationHistory history = new RotationHistory(capacity);
        for (int i = 0; i < yaws.length; i++) {
            history.record(1_000_000_000L + i * TICK, yaws[i], 1.0 + i * 0.5, true, true, false, 0.15, 0.15, 42);
        }
        return history;
    }

    @Test void framesFollowTheCollectorWarmUp() {
        CombatFrame[] frames = filled(8, 10, 12, 15, 19, 18).frames(1, 1_000_000_000L + 4 * TICK);
        assertEquals(5, frames.length);
        assertEquals(1, frames[0].tick());
        assertEquals(5, frames[4].tick());
        assertEquals(1, frames[0].value(FrameField.SEGMENT_START));
        assertEquals(0, frames[1].value(FrameField.SEGMENT_START));
        assertTrue(Double.isNaN(frames[0].value(FrameField.DELTA_YAW)), "no predecessor for the first sample");
        assertEquals(2, frames[1].value(FrameField.DELTA_YAW), 1e-12);
        assertTrue(Double.isNaN(frames[1].value(FrameField.DELTA2_YAW)));
        assertEquals(1, frames[2].value(FrameField.DELTA2_YAW), 1e-12);
        assertTrue(Double.isNaN(frames[2].value(FrameField.ROTATION_JERK)));
        double acc2 = Math.hypot(3 - 2, 0), acc3 = Math.hypot(4 - 3, 0);
        assertEquals(acc3 - acc2, frames[3].value(FrameField.ROTATION_JERK), 1e-12);
        assertEquals(50, frames[1].value(FrameField.SAMPLE_INTERVAL_MS), 1e-9);
        assertEquals(0, frames[2].value(FrameField.TARGET_PRESENT));
        assertTrue(Double.isNaN(frames[2].value(FrameField.AIM_ERROR_TOTAL)), "no invented target");
        assertEquals(0.15, frames[2].value(FrameField.MOUSE_GRID_YAW));
    }

    @Test void onlyTheNewestSamplesAreKeptAndAGapRestarts() {
        RotationHistory history = filled(3, 1, 2, 3, 4, 5);
        CombatFrame[] frames = history.frames(1, 1_000_000_000L + 4 * TICK);
        assertEquals(3, frames.length);
        assertEquals(3, frames[0].value(FrameField.YAW));
        history.record(1_000_000_000L + 4 * TICK + 400_000_000L, 9, 0, true, false, false, Double.NaN, Double.NaN, Double.NaN);
        assertEquals(1, history.size(), "a pause longer than a gap starts over");
        assertEquals(0, history.frames(1, 1_000_000_000L + 10_000_000_000L).length, "stale history is not used");
        history.record(2_000_000_000_000L, Double.NaN, 0, true, false, false, 0, 0, 0);
        assertEquals(0, history.size());
    }

    @Test void theFirstAttackGetsACompleteEncodableWindow() {
        int before = 20, after = 10;
        double[] yaws = new double[before];
        for (int i = 0; i < before; i++) yaws[i] = i * 1.5;
        long last = 1_000_000_000L + (before - 1) * TICK;
        CombatFrame[] history = filled(before, yaws).frames(1, last + TICK);
        TemporalRingBuffer ring = new TemporalRingBuffer(96);
        for (CombatFrame frame : history) ring.add(frame);
        // What the collector samples next: the attack tick, then the ticks after it.
        for (int i = 0; i <= after; i++) {
            double[] values = new double[FrameField.COUNT];
            Arrays.fill(values, Double.NaN);
            values[FrameField.ATTACK.ordinal()] = i == 0 ? 1 : 0;
            values[FrameField.SEGMENT_START.ordinal()] = 0;
            values[FrameField.TARGET_PRESENT.ordinal()] = 0;
            ring.add(new CombatFrame(before + 1 + i, last + (i + 1) * TICK, values));
        }
        CombatFrame[] window = AttackWindowBuilder.extract(ring, before + 1, before, after);
        assertNotNull(window, "the first attack now has its history");
        assertEquals(before + 1 + after, window.length);
        float[] encoded = FeatureEncoder.encode(window);
        assertEquals(window.length * ModelFeature.FEATURE_COUNT, encoded.length);
    }
}
