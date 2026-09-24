package dev.aeroac.neural.inference;

/**
 * Bounded prediction history, confined to the player event loop: responses are applied through
 * runSafely and commands read it from the same loop, so no synchronisation is needed here.
 */
public final class PredictionTrail {
    private final PredictionResult[] entries;
    /** Newest accepted request id per model: Flash and Pro share one id sequence but not one answer. */
    private final long[] newestByModel = new long[ModelKind.values().length];
    private int next;
    private int size;
    private long accepted;
    private long rejectedStale;

    public PredictionTrail(int capacity) {
        if (capacity < 1 || capacity > 256) throw new IllegalArgumentException("capacity must be 1..256");
        entries = new PredictionResult[capacity];
        java.util.Arrays.fill(newestByModel, Long.MIN_VALUE);
    }

    /**
     * Out-of-order and duplicate responses are dropped: a late answer must not overwrite a newer one
     * from the same model. Staleness is per model because the two models share one request id sequence
     * but answer at very different speeds: a Pro window sent at id 7 routinely comes back after the
     * Flash answer to id 8, and comparing them against each other threw away most Pro escalations.
     */
    public boolean add(PredictionResult result) {
        int model = result.model().ordinal();
        if (result.requestId() <= newestByModel[model]) {
            rejectedStale++;
            return false;
        }
        newestByModel[model] = result.requestId();
        entries[next] = result;
        next = (next + 1) % entries.length;
        size = Math.min(size + 1, entries.length);
        accepted++;
        return true;
    }

    public int size() { return size; }
    public long accepted() { return accepted; }
    public long rejectedStale() { return rejectedStale; }

    public PredictionResult get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return entries[(next - size + index + entries.length) % entries.length];
    }

    public PredictionResult latest() { return size == 0 ? null : get(size - 1); }

    public void clear() {
        for (int i = 0; i < entries.length; i++) entries[i] = null;
        java.util.Arrays.fill(newestByModel, Long.MIN_VALUE);
        next = size = 0;
    }
}
