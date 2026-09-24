package dev.aeroac.checks.impl.aim;

import java.util.Arrays;

/**
 * Finds the snap aura's boomerang: the head jumps onto the target in one sample some time before a hit
 * and, right after it, jumps back to where it was looking before. Pure logic over rotation samples,
 * attacks and their times, so the signature can be replayed in a unit test.
 *
 * <p>A silent snap never moves the camera, only the rotation sent to the server: it takes the head the
 * moment a hit becomes available, holds it on the target and hands it straight back to the free look
 * once the hit is sent. A player who flicks onto someone keeps fighting them, and a player who turns
 * round to hit a chaser turns over several ticks, not in one sample and back.
 *
 * <p>So a hit counts only when all of this holds:
 * <ul>
 *     <li>one sample before it turned the head by at least {@link #MIN_SNAP_DEGREES}, with the samples
 *     either side of it moving at most {@link #SHARPNESS} of that - the turn is one step, not a sweep;</li>
 *     <li>within {@link #GIVE_BACK_MS} and {@link #GIVE_BACK_SAMPLES} samples after the hit, the head
 *     turned back by a step of at least {@link #MIN_SNAP_DEGREES}, again carrying most of the way;</li>
 *     <li>it ended within {@link #RETURN_FRACTION} of the snap from where it was before the snap.</li>
 * </ul>
 * One boomerang can be a coincidence, so it takes {@link #REQUIRED} among the last {@link #WINDOW} hits.
 */
public final class SnapBackDetector {
    /** Smallest single-sample rotation that counts as a snap, in degrees. */
    public static final float MIN_SNAP_DEGREES = 15.0F;
    /** The largest the samples either side of the snap may move, as a share of it. */
    public static final float SHARPNESS = 0.4F;
    /** The largest step of the return has to carry at least this share of it. */
    public static final float RETURN_SHARPNESS = 0.45F;
    /** How long before the hit the snap may be; auras take the head as soon as the hit is available. */
    public static final long LOOK_BACK_MS = 750L;
    /** How long after the hit the head has to be handed back within: a held tick, a timeout, the return. */
    public static final long GIVE_BACK_MS = 400L;
    /** How many samples after the hit are enough to judge it. */
    public static final int GIVE_BACK_SAMPLES = 3;
    /** How close to the pre-snap view the head has to come back, as a fraction of the snap. */
    public static final float RETURN_FRACTION = 0.35F;
    public static final int WINDOW = 8;
    public static final int REQUIRED = 3;

    private static final int HISTORY = 48;

    private final double[] x = new double[HISTORY];
    private final double[] y = new double[HISTORY];
    private final double[] z = new double[HISTORY];
    private final long[] time = new long[HISTORY];
    private long samples;
    private long validFrom;
    private long pendingAttack = -1;
    private long attackTime;
    private final boolean[] results = new boolean[WINDOW];
    private int resultIndex;

    /**
     * An attack was sent; the rotation it was judged against is the latest sample.
     *
     * @return true when enough recent hits were boomerangs
     */
    public boolean attack(long nowMillis) {
        // Several hits against the same rotation are one hit to judge
        if (pendingAttack >= 0 && pendingAttack == samples - 1) return false;
        boolean flagged = pendingAttack >= 0 && finish();
        if (samples > validFrom) {
            pendingAttack = samples - 1;
            attackTime = nowMillis;
        }
        return flagged;
    }

    /**
     * A new rotation.
     *
     * @return true when enough recent hits were boomerangs
     */
    public boolean rotation(float yaw, float pitch, long nowMillis) {
        // Anything this late is not the hand-back; judge the hit on what came before it
        boolean flagged = pendingAttack >= 0 && nowMillis - attackTime > GIVE_BACK_MS && finish();

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        int slot = (int) (samples % HISTORY);
        x[slot] = -Math.sin(yawRad) * Math.cos(pitchRad);
        y[slot] = -Math.sin(pitchRad);
        z[slot] = Math.cos(yawRad) * Math.cos(pitchRad);
        time[slot] = nowMillis;
        samples++;

        if (pendingAttack >= 0 && samples - 1 - pendingAttack >= GIVE_BACK_SAMPLES) flagged |= finish();
        return flagged;
    }

    /** A teleport or anything else that moves the head without the player: no step may cross it. */
    public void discontinuity() {
        validFrom = samples;
        pendingAttack = -1;
    }

    private boolean finish() {
        long attack = pendingAttack;
        pendingAttack = -1;
        return record(attack >= samples - HISTORY && isBoomerang(attack, samples - 1));
    }

    private boolean isBoomerang(long attack, long last) {
        if (last <= attack) return false;
        long oldest = Math.max(validFrom, samples - HISTORY);

        float bestOut = 0;
        for (long j = attack + 1; j <= last; j++) bestOut = Math.max(bestOut, angle(j - 1, j));
        if (bestOut < MIN_SNAP_DEGREES || bestOut < RETURN_SHARPNESS * angle(attack, last)) return false;

        for (long k = attack; k >= oldest + 2; k--) {
            if (attackTime - time[slot(k)] > LOOK_BACK_MS) break;
            float in = angle(k - 1, k);
            if (in < MIN_SNAP_DEGREES) continue;
            float before = angle(k - 2, k - 1);
            float after = k < attack ? angle(k, k + 1) : 0;
            if (before > SHARPNESS * in || after > SHARPNESS * in) continue;
            if (angle(k - 1, last) <= RETURN_FRACTION * in) return true;
        }
        return false;
    }

    private boolean record(boolean boomerang) {
        results[resultIndex] = boomerang;
        resultIndex = (resultIndex + 1) % WINDOW;
        int count = 0;
        for (boolean result : results) if (result) count++;
        if (count >= REQUIRED) {
            Arrays.fill(results, false);
            return true;
        }
        return false;
    }

    private static int slot(long sample) {
        return (int) (sample % HISTORY);
    }

    private float angle(long a, long b) {
        int i = slot(a);
        int j = slot(b);
        double dot = x[i] * x[j] + y[i] * y[j] + z[i] * z[j];
        return (float) Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }
}
