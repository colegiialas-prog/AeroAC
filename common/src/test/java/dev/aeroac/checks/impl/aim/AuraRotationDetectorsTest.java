package dev.aeroac.checks.impl.aim;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays the rotations the supplied aura profiles send, one sample per client tick (50 ms), next to
 * what players do in the same fights, through the pure detectors behind AuraSnapBack, AuraShake and
 * AuraLock.
 */
class AuraRotationDetectorsTest {
    private static final long TICK = 50L;

    // --- Snap: take the head for the hit, give it straight back ---------------------------------

    /** Free look drifting slowly while the player moves the mouse. */
    private static float freeYaw(int tick) {
        return 10.0F + 0.5F * tick;
    }

    /** A target seventy degrees to the side, walking across the view at a degree a tick. */
    private static float targetYaw(int tick) {
        return freeYaw(tick) + 70.0F + tick % 7;
    }

    /**
     * One snap cycle starting at {@code start}: free look, the snap onto the target, the hold while
     * the hit becomes available, the hit, one silent held tick, then either the return or, for a
     * player, the rest of the fight with that target before looking elsewhere again.
     *
     * @return the tick the next cycle starts at
     */
    private static int snapCycle(SnapBackDetector detector, int start, boolean[] flagged, boolean giveBack) {
        int t = start;
        for (; t < start + 5; t++) flagged[0] |= detector.rotation(freeYaw(t), 5.0F, t * TICK);
        for (; t < start + 11; t++) flagged[0] |= detector.rotation(targetYaw(t), 12.0F, t * TICK);
        flagged[0] |= detector.attack(t * TICK);
        t++; // held: the head did not move, so nothing was sent
        if (giveBack) {
            flagged[0] |= detector.rotation(freeYaw(t), 5.0F, t * TICK);
            return t + 1;
        }
        for (int end = t + 12; t < end; t++) flagged[0] |= detector.rotation(targetYaw(t), 12.0F, t * TICK);
        return t;
    }

    @Test
    void snapAuraFlagsOnItsThirdBoomerang() {
        SnapBackDetector detector = new SnapBackDetector();
        boolean[] flagged = {false};
        int t = 0;
        for (int hit = 1; hit <= 2; hit++) {
            t = snapCycle(detector, t, flagged, true);
            assertFalse(flagged[0], "one or two boomerangs can be coincidence");
        }
        t = snapCycle(detector, t, flagged, true);
        // The third hit is judged once enough samples followed it
        for (int i = 0; i < 3; i++, t++) flagged[0] |= detector.rotation(freeYaw(t), 5.0F, t * TICK);
        assertTrue(flagged[0]);
    }

    @Test
    void flickingOntoSomeoneAndFightingThemIsNotASnap() {
        SnapBackDetector detector = new SnapBackDetector();
        boolean[] flagged = {false};
        int t = 0;
        for (int hit = 0; hit < 40; hit++) t = snapCycle(detector, t, flagged, false);
        assertFalse(flagged[0]);
    }

    @Test
    void turningRoundToHitAChaserAndBackIsNotASnap() {
        SnapBackDetector detector = new SnapBackDetector();
        boolean flagged = false;
        int t = 0;
        for (int hit = 0; hit < 40; hit++) {
            for (int i = 0; i < 6; i++, t++) flagged |= detector.rotation(0.3F * i, 0.0F, t * TICK);
            // A hand turns half a circle over several ticks, however fast
            float[] turn = {30, 75, 130, 170, 180};
            for (float yaw : turn) flagged |= detector.rotation(yaw, 5.0F, t++ * TICK);
            flagged |= detector.rotation(181, 5.0F, t++ * TICK);
            flagged |= detector.attack(t * TICK);
            float[] back = {150, 100, 45, 10, 0};
            for (float yaw : back) flagged |= detector.rotation(yaw, 0.0F, t++ * TICK);
        }
        assertFalse(flagged);
    }

    @Test
    void aReturnLongAfterTheHitIsNotAHandBack() {
        SnapBackDetector detector = new SnapBackDetector();
        boolean flagged = false;
        int t = 0;
        for (int hit = 0; hit < 20; hit++) {
            for (int end = t + 5; t < end; t++) flagged |= detector.rotation(freeYaw(t), 5.0F, t * TICK);
            for (int end = t + 6; t < end; t++) flagged |= detector.rotation(targetYaw(t), 12.0F, t * TICK);
            flagged |= detector.attack(t * TICK);
            t += 12; // looking at the target, not moving, for 600 ms
            flagged |= detector.rotation(freeYaw(t), 5.0F, t * TICK);
            t++;
        }
        assertFalse(flagged);
    }

    @Test
    void aTeleportBetweenTheHitAndTheReturnIsNotAReturn() {
        SnapBackDetector detector = new SnapBackDetector();
        boolean flagged = false;
        int t = 0;
        for (int hit = 0; hit < 20; hit++) {
            for (int end = t + 5; t < end; t++) flagged |= detector.rotation(freeYaw(t), 5.0F, t * TICK);
            for (int end = t + 6; t < end; t++) flagged |= detector.rotation(targetYaw(t), 12.0F, t * TICK);
            flagged |= detector.attack(t * TICK);
            t++;
            detector.discontinuity();
            flagged |= detector.rotation(freeYaw(t), 5.0F, t * TICK);
            t++;
        }
        assertFalse(flagged);
    }

    // --- FunTime: exact on the strike, then a displaced angle with a two-tick jitter over it ----

    /** The supplied FunTime profile through the rotation controller: 180 degrees a tick, mouse steps. */
    private static final class FunTime {
        private final Random random;
        private float yaw, pitch = 10, freeYaw, freePitch = 10;
        private float jitterYaw, jitterPitch, jitterTargetYaw, jitterTargetPitch;
        private int jitterTicks, ticksSinceHit = 9;
        private float displacedYaw, displacedPitch;
        private boolean displaced;

        FunTime(long seed) {
            random = new Random(seed);
        }

        void tick(int tick) {
            freeYaw += (float) random.nextGaussian() * 1.5F;
            freePitch += (float) random.nextGaussian() * 0.5F;
            float targetYaw = (float) (3 * Math.sin(tick / 7.0));
            float wantedYaw, wantedPitch;
            if (tick % 11 == 0) {
                wantedYaw = targetYaw;
                wantedPitch = 12;
                displacedYaw = targetYaw + (random.nextBoolean() ? 1 : -1) * (18 + 6 * random.nextFloat());
                displacedPitch = 12 + (random.nextBoolean() ? 1 : -1) * (12 + 4 * random.nextFloat());
                displaced = true;
                ticksSinceHit = 0;
            } else {
                ticksSinceHit = Math.min(9, ticksSinceHit + 1);
                boolean useDisplaced = displaced && ticksSinceHit < 9;
                if (++jitterTicks >= 2) {
                    jitterTicks = 0;
                    jitterTargetYaw = -20 + 40 * random.nextFloat();
                    jitterTargetPitch = -10 + 20 * random.nextFloat();
                }
                jitterYaw += 0.6F * (jitterTargetYaw - jitterYaw);
                jitterPitch += 0.6F * (jitterTargetPitch - jitterPitch);
                wantedYaw = (useDisplaced ? displacedYaw : freeYaw) + jitterYaw;
                wantedPitch = (useDisplaced ? displacedPitch : freePitch) + jitterPitch;
            }
            yaw += mouse(Math.max(-180, Math.min(180, wantedYaw - yaw)));
            pitch = Math.max(-90, Math.min(90, pitch + mouse(wantedPitch - pitch)));
        }

        private static float mouse(float delta) {
            return Math.round(delta / 0.15F) * 0.15F;
        }
    }

    /** A player following a target that strafes left and right and jumps, a couple of blocks away. */
    private static final class Tracker {
        private final Random random;
        private final double distance, tremor;
        private double targetX, velocity = 0.25, targetY, velocityY;
        private int untilSwitch = 6;
        double yaw, pitch;
        private double yawSpeed, pitchSpeed;

        Tracker(long seed, double distance, double tremor) {
            random = new Random(seed);
            this.distance = distance;
            this.tremor = tremor;
        }

        void tick() {
            if (--untilSwitch <= 0) {
                velocity = -velocity * (0.8 + 0.4 * random.nextDouble());
                untilSwitch = 3 + random.nextInt(8);
            }
            targetX += velocity;
            if (targetY <= 0 && random.nextDouble() < 0.08) velocityY = 0.42;
            targetY += velocityY;
            velocityY = (velocityY - 0.08) * 0.98;
            if (targetY < 0) targetY = velocityY = 0;
            double bearing = CenterLockTracker.bearing(targetX, distance);
            double elevation = -Math.toDegrees(Math.atan2(targetY - 0.2, Math.hypot(targetX, distance)));
            double gain = 0.35 + 0.35 * random.nextDouble();
            yawSpeed = 0.5 * yawSpeed + gain * (bearing - yaw) + random.nextGaussian() * tremor;
            pitchSpeed = 0.5 * pitchSpeed + gain * (elevation - pitch) + random.nextGaussian() * tremor * 0.6;
            yaw += Math.round(yawSpeed / 0.15) * 0.15;
            pitch += Math.round(pitchSpeed / 0.15) * 0.15;
        }
    }

    @Test
    void funTimeJitterShakesWithinSeconds() {
        int caught = 0;
        for (long seed = 0; seed < 20; seed++) {
            FunTime aura = new FunTime(seed);
            ShakeDetector detector = new ShakeDetector();
            for (int tick = 0; tick < 1200; tick++) {
                aura.tick(tick);
                if (detector.rotation(aura.yaw, aura.pitch)) {
                    caught++;
                    break;
                }
            }
        }
        assertTrue(caught >= 18, "caught " + caught + " of 20 FunTime fights within a minute");
    }

    @Test
    void trackingAStrafingJumpingTargetDoesNotShake() {
        for (long seed = 0; seed < 30; seed++) {
            for (double distance : new double[]{1.5, 2.5, 4.0}) {
                for (double tremor : new double[]{0.6, 2.0}) {
                    // A jitter-clicking hand: the crosshair also vibrates by a couple of degrees every tick
                    for (double vibration : new double[]{0.0, 2.0}) {
                        Tracker hand = new Tracker(seed, distance, tremor);
                        Random shaking = new Random(~seed);
                        ShakeDetector detector = new ShakeDetector();
                        for (int tick = 0; tick < 2000; tick++) {
                            hand.tick();
                            assertFalse(detector.rotation(hand.yaw + shaking.nextGaussian() * vibration,
                                            hand.pitch + shaking.nextGaussian() * vibration),
                                    "seed " + seed + " distance " + distance + " tremor " + tremor
                                            + " vibration " + vibration + " tick " + tick);
                        }
                    }
                }
            }
        }
    }

    @Test
    void zigzagCountsOnlyFastReversals() {
        ZigzagCounter counter = new ZigzagCounter(6, 3);
        int fast = 0;
        for (double value : new double[]{0, 8, 0, 8, 0, 8}) if (counter.add(value)) fast++;
        assertEquals(4, fast);

        ZigzagCounter slow = new ZigzagCounter(6, 3);
        int reversals = 0;
        // Eight samples up, eight down: a strafe followed, not a shake
        for (int cycle = 0; cycle < 4; cycle++) {
            for (int i = 0; i < 8; i++) if (slow.add(i)) reversals++;
            for (int i = 8; i > 0; i--) if (slow.add(i)) reversals++;
        }
        assertEquals(0, reversals);

        ZigzagCounter noise = new ZigzagCounter(6, 3);
        for (double value : new double[]{0, 2, -2, 3, -3, 2, 0}) assertFalse(noise.add(value), "below hysteresis");
    }

    // --- ReallyWorld / HvH: the head on the centre of the target ----------------------------------

    @Test
    void twoLockedWindowsFlag() {
        CenterLockTracker tracker = new CenterLockTracker();
        for (int i = 0; i < CenterLockTracker.WINDOW; i++) assertFalse(tracker.sample(true));
        for (int i = 0; i < CenterLockTracker.WINDOW - 1; i++) assertFalse(tracker.sample(i % 16 != 0));
        assertTrue(tracker.sample(true));
    }

    @Test
    void aGoodHandIsNotALock() {
        CenterLockTracker tracker = new CenterLockTracker();
        // Three of every thirty-two samples off the centre is already more than a lock allows
        for (int i = 0; i < 32 * 50; i++) assertFalse(tracker.sample(i % 32 >= 3));
        // A window that strikes only counts next to another one that does
        tracker.reset();
        for (int window = 0; window < 50; window++) {
            boolean locked = window % 2 == 0;
            for (int i = 0; i < CenterLockTracker.WINDOW; i++) assertFalse(tracker.sample(locked || i % 8 != 0));
        }
    }

    @Test
    void centreOffsetsFollowMinecraftYaw() {
        // Yaw 0 looks towards +z, 90 towards -x
        double[] ahead = CenterLockTracker.centerOffsets(0, 0, -0.01, 2.99, 0.01, 3.01, 0);
        assertNotNull(ahead);
        assertTrue(ahead[0] <= 0 && ahead[1] >= 0 && ahead[1] - ahead[0] < 0.5);

        double[] left = CenterLockTracker.centerOffsets(0, 0, -3.01, -0.01, -2.99, 0.01, 450);
        assertNotNull(left);
        assertTrue(Math.abs(left[0]) < 0.5 && Math.abs(left[1]) < 0.5, "unwrapped yaw is compared modulo a turn");

        double[] behind = CenterLockTracker.centerOffsets(0, 0, -0.01, -3.01, 0.01, -2.99, 0);
        assertNotNull(behind);
        assertEquals(180.0, Math.abs((behind[0] + behind[1]) / 2), 0.5);

        assertNull(CenterLockTracker.centerOffsets(0, 0, -1, -1, 1, 1, 0), "eye inside the possible centres");
        assertEquals(-1.0, CenterLockTracker.wrap(359.0), 1e-9);
        assertEquals(-180.0, CenterLockTracker.wrap(180.0), 1e-9);
    }
}
