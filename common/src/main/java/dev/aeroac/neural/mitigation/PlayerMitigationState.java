package dev.aeroac.neural.mitigation;

/**
 * Per-connection mitigation record. Mutated on the player event loop; the current action is volatile
 * because the packet path and operator commands both read it.
 */
public final class PlayerMitigationState {
    private final MitigationAction[] history;
    private int next;
    private int size;
    private volatile MitigationAction current;
    private long windowStartNanos;
    private int startedInWindow;
    private long suppressed;

    public PlayerMitigationState(int historySize) {
        if (historySize < 1 || historySize > 256) throw new IllegalArgumentException("historySize must be 1..256");
        history = new MitigationAction[historySize];
    }

    public MitigationAction current(long nowNanos) {
        MitigationAction action = current;
        return action != null && action.active(nowNanos) ? action : null;
    }

    public int size() { return size; }
    public long suppressed() { return suppressed; }
    public void suppressedOne() { suppressed++; }

    public MitigationAction get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return history[(next - size + index + history.length) % history.length];
    }

    /** Enforces the hourly cap on a rolling window so one bad minute cannot mitigate all evening. */
    boolean admit(long nowNanos, int maxPerHour) {
        if (maxPerHour <= 0) return false;
        if (nowNanos - windowStartNanos >= 3_600_000_000_000L) {
            windowStartNanos = nowNanos;
            startedInWindow = 0;
        }
        return startedInWindow < maxPerHour;
    }

    void start(MitigationAction action) {
        current = action;
        startedInWindow++;
        history[next] = action;
        next = (next + 1) % history.length;
        size = Math.min(size + 1, history.length);
    }

    public void clear() { current = null; }
}
