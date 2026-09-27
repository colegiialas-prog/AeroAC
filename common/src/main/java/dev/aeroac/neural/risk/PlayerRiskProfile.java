package dev.aeroac.neural.risk;

/**
 * Per-connection accumulated risk. Mutated only on the player event loop; the scalar and the state
 * are published volatile so a command or monitor thread reading them cannot see a torn value.
 *
 * <p>Risk is not persisted across reconnects. Carrying it over would need a reviewed, versioned
 * identity store, and a stale number is worse than none when the evidence behind it is gone.
 */
public final class PlayerRiskProfile {
    private final Evidence[] recent;
    /** Risk right after each entry of {@link #recent} was applied, same indexing. */
    private final double[] riskAfter;
    private final long[] typeCounts = new long[EvidenceType.values().length];
    private int next;
    private int size;

    private volatile double risk;
    private volatile RiskState state = RiskState.CLEAN;
    private long lastUpdateNanos;
    private long stateSinceNanos;
    private long transitions;
    private double peakRisk;

    public PlayerRiskProfile(int historySize, long nowNanos) {
        if (historySize < 1 || historySize > 512) throw new IllegalArgumentException("historySize must be 1..512");
        recent = new Evidence[historySize];
        riskAfter = new double[historySize];
        lastUpdateNanos = nowNanos;
        stateSinceNanos = nowNanos;
    }

    public double risk() { return risk; }
    public RiskState state() { return state; }
    public long lastUpdateNanos() { return lastUpdateNanos; }
    public long stateSinceNanos() { return stateSinceNanos; }
    public long transitions() { return transitions; }
    public double peakRisk() { return peakRisk; }
    public int evidenceSize() { return size; }
    public long count(EvidenceType type) { return typeCounts[type.ordinal()]; }

    public Evidence evidence(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return recent[(next - size + index + recent.length) % recent.length];
    }

    /** Risk right after the evidence at the same index was applied. */
    public double riskAfter(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return riskAfter[(next - size + index + recent.length) % recent.length];
    }

    void risk(double value, long nowNanos) {
        risk = value;
        lastUpdateNanos = nowNanos;
        if (value > peakRisk) peakRisk = value;
    }

    void state(RiskState next, long nowNanos) {
        if (next == state) return;
        state = next;
        stateSinceNanos = nowNanos;
        transitions++;
    }

    void record(Evidence evidence) {
        recent[next] = evidence;
        riskAfter[next] = risk;
        next = (next + 1) % recent.length;
        size = Math.min(size + 1, recent.length);
        typeCounts[evidence.type().ordinal()]++;
    }
}
