package dev.aeroac.neural;

import dev.aeroac.neural.target.AimErrorCalculator;
import dev.aeroac.neural.target.TargetTracker;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;
import dev.aeroac.neural.window.AttackWindowBuilder;
import dev.aeroac.neural.window.TemporalRingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryFoundationTest {
    @ParameterizedTest
    @CsvSource({"0,0", "180,-180", "-180,-180", "181,-179", "-181,179", "540,-180", "-540,-180", "1081,1"})
    void yawWrapsBothDirections(double input, double expected) {
        assertEquals(expected, AimErrorCalculator.normalizeYaw(input), 1.0E-9);
    }

    @Test void aimUsesMinecraftAxesAndWrapsError() {
        var south = AimErrorCalculator.calculate(0, 0, 0, 0, 3);
        assertEquals(0, south.total(), 1.0E-9);
        var east = AimErrorCalculator.calculate(-90, 0, 3, 0, 0);
        assertEquals(-90, east.targetYaw(), 1.0E-9);
        assertEquals(0, east.total(), 1.0E-9);
        var above = AimErrorCalculator.calculate(0, -45, 0, 3, 3);
        assertEquals(-45, above.targetPitch(), 1.0E-9);
        assertEquals(0, above.total(), 1.0E-9);
        double radians = Math.toRadians(-179);
        var wrapped = AimErrorCalculator.calculate(179, 0, -Math.sin(radians), 0, Math.cos(radians));
        assertEquals(-2, wrapped.yaw(), 1.0E-9);
    }

    @Test void coincidentAndNonFiniteAimAreUnknown() {
        assertTrue(Double.isNaN(AimErrorCalculator.calculate(0, 0, 0, 0, 0).total()));
        assertTrue(Double.isNaN(AimErrorCalculator.calculate(Double.NaN, 0, 1, 1, 1).total()));
    }

    @Test void targetSwitchAndIdReuseAreDistinctFromAcquisition() {
        TargetTracker tracker = new TargetTracker();
        Object first = new Object(), second = new Object();
        tracker.attack(1, first, 1, 1);
        assertFalse(tracker.consumeSwitch());
        tracker.attack(1, first, 2, 2);
        assertEquals(1, tracker.ticksSinceSwitch(2));
        assertFalse(tracker.consumeSwitch());
        tracker.attack(2, second, 3, 3);
        assertEquals(1, tracker.previous());
        assertTrue(tracker.consumeSwitch());
        assertFalse(tracker.consumeSwitch());
        tracker.attack(2, new Object(), 4, 4);
        assertTrue(tracker.consumeSwitch());
        assertEquals(0, tracker.ticksSinceSwitch(4));
    }

    @Test void targetExpiresOnTimeTickDeathAndDespawn() {
        TargetTracker tracker = new TargetTracker();
        Object entity = new Object();
        tracker.attack(7, entity, 1, 1);
        assertTrue(tracker.validate(entity, true, 41, 100, 40));
        assertFalse(tracker.validate(entity, true, 42, 100, 40));
        tracker.attack(7, entity, 1, 1);
        assertFalse(tracker.validate(entity, true, 2, 2_000_000_002L, 40));
        tracker.attack(7, entity, 1, 1);
        assertFalse(tracker.validate(entity, false, 2, 2, 40));
        tracker.attack(7, entity, 1, 1);
        assertFalse(tracker.validate(new Object(), true, 2, 2, 40));
        assertEquals(-1, tracker.current());
    }

    @Test void ringKeepsChronologicalTailThroughWrap() {
        TemporalRingBuffer ring = new TemporalRingBuffer(4);
        for (int tick = 0; tick < 11; tick++) ring.add(frame(tick, false, false));
        assertEquals(4, ring.size());
        assertArrayEquals(new long[]{7, 8, 9, 10}, Arrays.stream(ring.tail(4)).mapToLong(CombatFrame::tick).toArray());
        assertThrows(IllegalArgumentException.class, () -> ring.tail(5));
        ring.clear();
        assertEquals(0, ring.size());
        assertThrows(IndexOutOfBoundsException.class, () -> ring.get(0));
    }

    @Test void attackWindowRequiresCompleteBeforeAndAfterWithoutGaps() {
        TemporalRingBuffer ring = new TemporalRingBuffer(8);
        for (int tick = 0; tick < 6; tick++) ring.add(frame(tick, tick == 3, false));
        assertNull(AttackWindowBuilder.extract(ring, 3, 2, 3));
        ring.add(frame(6, false, false));
        assertArrayEquals(new long[]{1, 2, 3, 4, 5, 6}, Arrays.stream(AttackWindowBuilder.extract(ring, 3, 2, 3)).mapToLong(CombatFrame::tick).toArray());
        assertNull(AttackWindowBuilder.extract(ring, 2, 1, 1));
        ring.add(frame(7, false, true));
        assertNull(AttackWindowBuilder.extract(ring, 3, 2, 4));
        assertThrows(IllegalArgumentException.class, () -> AttackWindowBuilder.extract(ring, 3, 5, 5));
    }

    @Test void missingTickCannotFormWindow() {
        TemporalRingBuffer ring = new TemporalRingBuffer(4);
        ring.add(frame(1, false, false)); ring.add(frame(3, true, false)); ring.add(frame(4, false, false));
        assertNull(AttackWindowBuilder.extract(ring, 3, 1, 1));
    }

    @Test void frameOwnsValuesAndRejectsSchemaMismatch() {
        double[] values = new double[FrameField.COUNT];
        CombatFrame frame = new CombatFrame(1, 2, values);
        values[0] = 12;
        assertEquals(0, frame.value(FrameField.YAW));
        assertThrows(IllegalArgumentException.class, () -> new CombatFrame(0, 0, new double[2]));
    }

    static CombatFrame frame(long tick, boolean attack, boolean segmentStart) {
        double[] values = new double[FrameField.COUNT];
        Arrays.fill(values, Double.NaN);
        values[FrameField.ATTACK.ordinal()] = attack ? 1 : 0;
        values[FrameField.SEGMENT_START.ordinal()] = segmentStart ? 1 : 0;
        values[FrameField.YAW.ordinal()] = tick * 2;
        return new CombatFrame(tick, tick * 50_000_000L, values);
    }
}
