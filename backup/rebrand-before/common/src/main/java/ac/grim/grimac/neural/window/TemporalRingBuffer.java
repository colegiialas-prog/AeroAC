package ac.grim.grimac.neural.window;

import ac.grim.grimac.neural.telemetry.CombatFrame;

import java.util.Arrays;

/** Bounded references to immutable frames. Owner thread only. No per-push allocations. */
public final class TemporalRingBuffer {
    private final CombatFrame[] frames;
    private int next;
    private int size;

    public TemporalRingBuffer(int capacity) {
        if (capacity < 1 || capacity > 512) throw new IllegalArgumentException("capacity must be 1..512");
        frames = new CombatFrame[capacity];
    }

    public void add(CombatFrame frame) {
        frames[next] = frame;
        next = (next + 1) % frames.length;
        size = Math.min(size + 1, frames.length);
    }

    public int size() { return size; }
    public int capacity() { return frames.length; }
    public CombatFrame get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return frames[(next - size + index + frames.length) % frames.length];
    }

    public CombatFrame[] tail(int count) {
        if (count < 0 || count > size) throw new IllegalArgumentException("Insufficient history");
        CombatFrame[] result = new CombatFrame[count];
        for (int i = 0; i < count; i++) result[i] = get(size - count + i);
        return result;
    }

    public void clear() { Arrays.fill(frames, null); next = size = 0; }
}
