package dev.aeroac.checks.impl.movement;

/**
 * Counts client ticks since the last reported position. Pure logic, so the packet streams of a vanilla
 * client and of an air-stuck client can be replayed in a unit test.
 *
 * <p>A vanilla 1.21.2+ client that is not riding anything reports its position at least on every 20th
 * tick ({@code LocalPlayer.positionReminder}), so between two positions at most 20 tick ends arrive.
 * Counting only starts at the first position after the counter is disarmed (join, respawn, camera
 * change), because a client that is still loading ticks without reporting one.
 */
public final class PositionReminder {
    /** Tick ends a vanilla client can send between two positions. */
    public static final int VANILLA_MAX_TICKS = 20;

    private final int maxTicks;
    private int ticks;
    private boolean armed;

    public PositionReminder(int maxTicks) {
        this.maxTicks = Math.max(VANILLA_MAX_TICKS, maxTicks);
    }

    public int maxTicks() { return maxTicks; }

    public int ticks() { return ticks; }

    public boolean armed() { return armed; }

    public void position() {
        ticks = 0;
        armed = true;
    }

    /** Join, respawn, camera moved: wait for a fresh position before counting again. */
    public void disarm() {
        ticks = 0;
        armed = false;
    }

    /**
     * One {@code CLIENT_TICK_END}.
     *
     * @param exempt a state in which vanilla legitimately reports no position (vehicle, death, ...)
     * @return the number of ticks without a position when it just went over the limit, otherwise 0
     */
    public int tickEnd(boolean exempt) {
        if (!armed || exempt) {
            ticks = 0;
            return 0;
        }
        if (++ticks > maxTicks) {
            int over = ticks;
            ticks = 0;
            return over;
        }
        return 0;
    }
}
