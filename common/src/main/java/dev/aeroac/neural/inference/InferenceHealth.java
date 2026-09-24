package dev.aeroac.neural.inference;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Service health is an operational signal only. A timeout, a 429 or a malformed body is never
 * evidence about a player, and never cancels a deterministic check.
 */
public final class InferenceHealth {
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong shed = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private volatile String lastFailure;
    private volatile long lastFailureMillis;

    public void sent() { sent.incrementAndGet(); }
    public void accepted(long latencyMs) { accepted.incrementAndGet(); latencySumMs.addAndGet(Math.max(0, latencyMs)); }
    public void timedOut() { timedOut.incrementAndGet(); }
    public void shed() { shed.incrementAndGet(); }

    /** Protocol/schema refusals are counted apart from transport failures: they need a redeploy, not a retry. */
    public void rejected(String reason) { rejected.incrementAndGet(); note(reason); }

    public void failed(String reason) { failed.incrementAndGet(); note(reason); }

    private void note(String reason) {
        lastFailure = reason == null ? "unspecified" : reason;
        lastFailureMillis = System.currentTimeMillis();
    }

    public long sentCount() { return sent.get(); }
    public long acceptedCount() { return accepted.get(); }
    public long timedOutCount() { return timedOut.get(); }
    public long rejectedCount() { return rejected.get(); }
    public long failedCount() { return failed.get(); }
    public long shedCount() { return shed.get(); }
    public String lastFailure() { return lastFailure; }
    public long lastFailureMillis() { return lastFailureMillis; }

    public double averageLatencyMs() {
        long count = accepted.get();
        return count == 0 ? Double.NaN : (double) latencySumMs.get() / count;
    }

    public String describe() {
        return "sent=" + sentCount() + " ok=" + acceptedCount() + " timeout=" + timedOutCount()
                + " rejected=" + rejectedCount() + " failed=" + failedCount() + " shed=" + shedCount()
                + " avgLatency=" + (Double.isNaN(averageLatencyMs()) ? "n/a" : String.format("%.1fms", averageLatencyMs()))
                + (lastFailure == null ? "" : " lastFailure=" + lastFailure);
    }
}
