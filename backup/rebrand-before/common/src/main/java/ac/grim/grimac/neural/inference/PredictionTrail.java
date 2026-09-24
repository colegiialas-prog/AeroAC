package ac.grim.grimac.neural.inference;

/**
 * Bounded prediction history, confined to the player event loop: responses are applied through
 * runSafely and commands read it from the same loop, so no synchronisation is needed here.
 */
public final class PredictionTrail {
    private final PredictionResult[] entries;
    private int next;
    private int size;
    private long accepted;
    private long rejectedStale;

    public PredictionTrail(int capacity) {
        if (capacity < 1 || capacity > 256) throw new IllegalArgumentException("capacity must be 1..256");
        entries = new PredictionResult[capacity];
    }

    /** Out-of-order and duplicate responses are dropped: a late answer must not overwrite a newer one. */
    public boolean add(PredictionResult result) {
        PredictionResult latest = latest();
        if (latest != null && result.requestId() <= latest.requestId()) {
            rejectedStale++;
            return false;
        }
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
        next = size = 0;
    }
}
