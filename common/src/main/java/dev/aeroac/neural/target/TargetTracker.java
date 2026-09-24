package dev.aeroac.neural.target;

/** Packet-thread confined. Object identity prevents entity-ID reuse from retaining a stale target. */
public final class TargetTracker {
    private int current = -1;
    private int previous = -1;
    private Object identity;
    private long lastAttackTick;
    private long lastAttackNanos;
    private long switchedTick;
    private boolean changed;

    public void attack(int id, Object entity, long tick, long now) {
        if (entity == null) return;
        if (id != current || entity != identity) {
            previous = current;
            changed |= current != -1;
            current = id;
            identity = entity;
            switchedTick = tick;
        }
        lastAttackTick = tick;
        lastAttackNanos = now;
    }

    public boolean validate(Object liveEntity, boolean valid, long tick, long now, int timeoutTicks) {
        if (current == -1) return false;
        if (!valid || liveEntity != identity || tick - lastAttackTick > timeoutTicks
                || now - lastAttackNanos > timeoutTicks * 50_000_000L) {
            clear();
            return false;
        }
        return true;
    }

    public void clear() {
        if (current != -1) previous = current;
        current = -1;
        identity = null;
        changed = false;
    }

    public int current() { return current; }
    public int previous() { return previous; }
    public long ticksSinceSwitch(long tick) { return current == -1 ? -1 : tick - switchedTick; }
    public boolean consumeSwitch() { boolean result = changed; changed = false; return result; }
}
