package dev.aeroac.neural.enforcement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * Automatic verdicts collected into waves. The first verdict of a wave sets its time: between half
 * and one and a half periods away, so neither the verdict nor the wave clock tells anyone which fight
 * tripped the detector. Everything queued before that time runs together.
 *
 * <p>Pure state and arithmetic; BanService owns the policy, the logging and the ban itself.
 */
public final class BanWave {
    private final Map<String, BanDecision> queued = new ConcurrentHashMap<>();
    private volatile long nextMillis;
    private volatile DoubleSupplier random;

    public BanWave(DoubleSupplier random) {
        this.random = random;
    }

    void random(DoubleSupplier replacement) { random = replacement; }

    /** Queues a verdict; the first one of a wave schedules it. */
    public synchronized void queue(BanDecision decision, long nowMillis, int periodMinutes) {
        queued.put(decision.id(), decision);
        if (nextMillis == 0) {
            long period = Math.max(1, periodMinutes) * 60_000L;
            nextMillis = nowMillis + Math.max(1, (long) (period * (0.5 + random.getAsDouble())));
        }
    }

    /** The verdicts whose wave is due, removed from the queue; empty before then. */
    public synchronized List<BanDecision> release(long nowMillis) {
        if (nextMillis == 0 || nowMillis < nextMillis) return List.of();
        nextMillis = 0;
        List<BanDecision> wave = new ArrayList<>(queued.values());
        queued.clear();
        return wave;
    }

    /** Drops everything queued and returns it, for a policy that no longer bans on its own. */
    public synchronized List<BanDecision> clear() {
        List<BanDecision> dropped = new ArrayList<>(queued.values());
        queued.clear();
        nextMillis = 0;
        return dropped;
    }

    public List<BanDecision> queued() { return List.copyOf(queued.values()); }

    /** When the next wave runs, or 0 when nothing waits. */
    public long nextMillis() { return nextMillis; }
}
