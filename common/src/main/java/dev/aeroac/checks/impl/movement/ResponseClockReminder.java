package dev.aeroac.checks.impl.movement;

/**
 * The position reminder for clients that send no {@code CLIENT_TICK_END} (1.21.1 and older, or any client
 * on an older server), timed by transaction responses instead of counted in tick ends. Pure logic, so
 * vanilla and air-stuck packet streams can be replayed in a unit test.
 *
 * <p>These clients also report their position at least on every 20th tick, but nothing marks a tick. What
 * shows the client is running is it answering transactions: it answers them on the thread that ticks.
 * While the answers keep coming, the client has been running for about as long as they span, and a client
 * running for a second at 20 ticks per second ticked 20 times - unless it drops ticks. It only does that
 * in frames longer than half a second (at most 10 ticks per frame), and a frame that long shows up as a
 * gap between answers. Any gap over {@link #MAX_GAP_NANOS} therefore restarts the measurement, so a span
 * is only counted when no tick can have been dropped in it. Network jitter can still move where a span
 * seems to start and end, which is why the required span is several times the vanilla second.
 */
public final class ResponseClockReminder {
    /** A longer gap between answers may hide a frame in which the client dropped ticks. */
    public static final long MAX_GAP_NANOS = 250_000_000L;
    /** The shortest span of steady answers that may be flagged, at 20 ticks per second. */
    public static final double MIN_SPAN_SECONDS = 2.0;

    private static final long NONE = Long.MIN_VALUE;

    private final double spanSeconds;
    private long requiredSpanNanos;
    private boolean armed;
    private long spanStart = NONE;
    private long lastResponse = NONE;

    public ResponseClockReminder(double spanSeconds) {
        this.spanSeconds = Math.max(MIN_SPAN_SECONDS, spanSeconds);
        setTickRate(20);
    }

    public long requiredSpanNanos() { return requiredSpanNanos; }

    /**
     * The server's {@code /tick rate}, which clients follow. Slower ticking means positions come further
     * apart in time, so the span grows with it; faster ticking never shortens it.
     */
    public void setTickRate(float tickRate) {
        double slowdown = tickRate > 0 && tickRate < 20 ? 20 / tickRate : 1;
        requiredSpanNanos = (long) (spanSeconds * slowdown * 1e9);
    }

    public void position() {
        armed = true;
        spanStart = NONE;
    }

    /** Join, respawn, camera moved: wait for a fresh position before timing again. */
    public void disarm() {
        armed = false;
        spanStart = NONE;
    }

    /**
     * The client answered a transaction.
     *
     * @param now    arrival time in nanoseconds
     * @param exempt a state in which vanilla legitimately reports no position (vehicle, death, ...)
     * @return how long the client has answered steadily without a position, in nanoseconds, when that just
     * reached the limit; otherwise 0
     */
    public long response(long now, boolean exempt) {
        boolean gap = lastResponse != NONE && now - lastResponse > MAX_GAP_NANOS;
        lastResponse = now;

        if (!armed || exempt) {
            spanStart = NONE;
            return 0;
        }
        if (gap || spanStart == NONE) {
            spanStart = now;
            return 0;
        }

        long span = now - spanStart;
        if (span >= requiredSpanNanos) {
            spanStart = now;
            return span;
        }
        return 0;
    }
}
