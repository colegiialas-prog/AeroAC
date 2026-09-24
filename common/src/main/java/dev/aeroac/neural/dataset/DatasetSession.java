package dev.aeroac.neural.dataset;

import dev.aeroac.neural.telemetry.TelemetryRecord;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** SPSC ring: one player event loop produces; one disk worker consumes. Stop is safe from any thread. */
public final class DatasetSession {
    public final DatasetMetadata metadata;
    private final TelemetryRecord[] queue;
    private volatile long writeIndex;
    private volatile long readIndex;
    private final AtomicInteger producing = new AtomicInteger();
    private final AtomicReference<String> closeReason = new AtomicReference<>();
    private volatile long dropped;
    private volatile String failure;
    private volatile long framesWritten;
    private volatile long recordsWritten;
    private volatile boolean completed;

    public DatasetSession(DatasetMetadata metadata, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Empty queue");
        this.metadata = metadata;
        queue = new TelemetryRecord[capacity];
    }

    public boolean offer(TelemetryRecord record) {
        producing.incrementAndGet();
        try {
            if (closeReason.get() != null) return false;
            long tail = writeIndex;
            if (tail - readIndex >= queue.length) { dropped++; return false; }
            queue[(int) (tail % queue.length)] = record;
            writeIndex = tail + 1; // release-publishes immutable record
            return true;
        } finally {
            producing.decrementAndGet();
        }
    }

    public TelemetryRecord poll() {
        long head = readIndex;
        if (head == writeIndex) return null;
        int slot = (int) (head % queue.length);
        TelemetryRecord record = queue[slot];
        queue[slot] = null;
        readIndex = head + 1;
        return record;
    }

    public void close(String reason) { closeReason.compareAndSet(null, reason); }
    public boolean readyToFinish() { return closeReason.get() != null && producing.get() == 0 && readIndex == writeIndex; }
    public boolean accepting() { return closeReason.get() == null; }
    public String closeReason() { return closeReason.get(); }
    public long dropped() { return dropped; }
    public int queued() { return (int) Math.max(0, writeIndex - readIndex); }
    public long framesWritten() { return framesWritten; }
    public long recordsWritten() { return recordsWritten; }
    public String failure() { return failure; }
    public boolean completed() { return completed; }
    public void written(boolean frame) { recordsWritten++; if (frame) framesWritten++; }
    public void fail(String message) { failure = message; close("IO_ERROR"); }
    public void complete() { completed = true; }
}
