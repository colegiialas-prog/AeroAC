package dev.aeroac.checks.impl.aim;

/**
 * Finds a head that shakes: yaw and pitch both reversing, by several degrees, every few samples, for
 * seconds on end. Pure logic over rotation samples, so it can be replayed in a unit test.
 *
 * <p>Aura profiles made to break aim models throw the head around a random offset every couple of
 * ticks. A player tracking someone does reverse - the target strafes, jumps, gets knocked back - but
 * following a body that moves at a few blocks a second turns the head smoothly; it takes most of a
 * second for the target to change direction, and the pitch hardly zigzags at all.
 *
 * <p>Samples are judged in windows of {@link #WINDOW}; a window strikes when it holds at least
 * {@link #YAW_REVERSALS} fast yaw and {@link #PITCH_REVERSALS} fast pitch reversals, and two windows in
 * a row striking flag - four seconds of shaking at the least.
 */
public final class ShakeDetector {
    /**
     * Swings smaller than these are left to hand tremor and jitter clicking: on a simulated hand that
     * trembles by 3 degrees every tick on top of tracking, nothing flags, while the aura's jitter of up
     * to twenty degrees still swings well past them.
     */
    public static final double YAW_HYSTERESIS = 12.0;
    public static final double PITCH_HYSTERESIS = 6.0;
    /** A swing that took longer than this many samples is tracking, not shaking. */
    public static final int FAST_SAMPLES = 3;
    public static final int WINDOW = 40;
    public static final int YAW_REVERSALS = 4;
    public static final int PITCH_REVERSALS = 3;

    private final ZigzagCounter yaw = new ZigzagCounter(YAW_HYSTERESIS, FAST_SAMPLES);
    private final ZigzagCounter pitch = new ZigzagCounter(PITCH_HYSTERESIS, FAST_SAMPLES);
    private int samples;
    private int yawReversals;
    private int pitchReversals;
    private boolean lastWindowStruck;

    /**
     * A new rotation; yaw unwrapped as the client sends it.
     *
     * @return true when the last two windows both shook
     */
    public boolean rotation(double yawDegrees, double pitchDegrees) {
        if (yaw.add(yawDegrees)) yawReversals++;
        if (pitch.add(pitchDegrees)) pitchReversals++;
        if (++samples < WINDOW) return false;

        boolean struck = yawReversals >= YAW_REVERSALS && pitchReversals >= PITCH_REVERSALS;
        boolean flagged = struck && lastWindowStruck;
        lastWindowStruck = struck && !flagged;
        samples = yawReversals = pitchReversals = 0;
        return flagged;
    }

    /** Out of combat, teleported or otherwise not one continuous look: start over. */
    public void reset() {
        yaw.reset();
        pitch.reset();
        samples = yawReversals = pitchReversals = 0;
        lastWindowStruck = false;
    }
}
