package dev.aeroac.checks.impl.aim;

/**
 * Finds a head locked onto the middle of a moving target. Pure logic, so it can be replayed in a unit
 * test: the check does the geometry and reports, for every sample where the target visibly moved
 * across the view, whether the yaw was on the target's centre to within the mouse's own precision.
 *
 * <p>Auras aim at the middle of the hitbox because that is the point that surely connects. A player
 * aims somewhere on the body, and when the body moves sideways the hand follows it a little behind or
 * a little ahead; staying on the exact centre, sample after sample, as it strafes is not something a
 * mouse does.
 *
 * <p>Judged samples are counted in windows of {@link #WINDOW}; a window strikes when at least
 * {@link #REQUIRED_INSIDE} of them were on the centre, and two windows in a row striking flag.
 */
public final class CenterLockTracker {
    public static final int WINDOW = 32;
    public static final int REQUIRED_INSIDE = 30;

    private int judged;
    private int inside;
    private boolean lastWindowStruck;

    /**
     * One sample where the target moved across the view.
     *
     * @return true when the last two windows were both locked on
     */
    public boolean sample(boolean onCenter) {
        if (onCenter) inside++;
        if (++judged < WINDOW) return false;

        boolean struck = inside >= REQUIRED_INSIDE;
        boolean flagged = struck && lastWindowStruck;
        lastWindowStruck = struck && !flagged;
        judged = inside = 0;
        return flagged;
    }

    public void reset() {
        judged = inside = 0;
        lastWindowStruck = false;
    }

    /** Minecraft's yaw of the direction (dx, dz), in degrees. */
    public static double bearing(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** An angle folded into [-180, 180). */
    public static double wrap(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    /**
     * Where, relative to {@code yaw}, the centre of a target that may be anywhere in the given rectangle
     * lies, seen from the eye.
     *
     * @return {lowest, highest} offset from the yaw in degrees, or null when the eye is inside the rectangle
     */
    public static double[] centerOffsets(double eyeX, double eyeZ, double minX, double minZ, double maxX, double maxZ, double yaw) {
        if (eyeX >= minX && eyeX <= maxX && eyeZ >= minZ && eyeZ <= maxZ) return null;
        // Corners are measured from the rectangle's own middle, so a target right behind the head does
        // not straddle the fold at half a turn
        double middle = bearing((minX + maxX) * 0.5 - eyeX, (minZ + maxZ) * 0.5 - eyeZ);
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        double[] xs = {minX, maxX};
        double[] zs = {minZ, maxZ};
        for (double x : xs) {
            for (double z : zs) {
                double corner = wrap(bearing(x - eyeX, z - eyeZ) - middle);
                low = Math.min(low, corner);
                high = Math.max(high, corner);
            }
        }
        double offset = wrap(middle - yaw);
        return new double[]{offset + low, offset + high};
    }
}
