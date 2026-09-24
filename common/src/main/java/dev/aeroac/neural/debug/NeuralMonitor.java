package dev.aeroac.neural.debug;

import dev.aeroac.neural.NeuralMessages;
import dev.aeroac.platform.api.sender.Sender;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Operator-facing live view. The player event loop only pays a volatile boolean read when nobody
 * is watching; the line itself is built at most once per interval and delivered off that loop.
 */
public final class NeuralMonitor {
    private static final int MAX_WATCHERS_PER_TARGET = 8;

    private final Map<UUID, Map<UUID, Sender>> watchers = new ConcurrentHashMap<>();
    private final long intervalNanos;

    public NeuralMonitor(int intervalMs) {
        this.intervalNanos = Math.max(1, intervalMs) * 1_000_000L;
    }

    /** Returns false when the target already has the maximum number of watchers. */
    public boolean add(UUID target, Sender sender) {
        Map<UUID, Sender> senders = watchers.computeIfAbsent(target, key -> new ConcurrentHashMap<>());
        if (senders.size() >= MAX_WATCHERS_PER_TARGET && !senders.containsKey(sender.getUniqueId())) return false;
        senders.put(sender.getUniqueId(), sender);
        return true;
    }

    public boolean remove(UUID target, Sender sender) {
        Map<UUID, Sender> senders = watchers.get(target);
        if (senders == null || senders.remove(sender.getUniqueId()) == null) return false;
        if (senders.isEmpty()) watchers.remove(target);
        return true;
    }

    public void clear(UUID target) { watchers.remove(target); }

    public boolean watching(UUID target) {
        Map<UUID, Sender> senders = watchers.get(target);
        return senders != null && !senders.isEmpty();
    }

    /**
     * Whether one particular operator is watching this target.
     *
     * <p>Needed by a toggle: a button that cannot tell "somebody is watching" from "you are
     * watching" turns itself off for everyone the moment a second operator looks.
     */
    public boolean watching(UUID target, UUID watcher) {
        Map<UUID, Sender> senders = watchers.get(target);
        return senders != null && senders.containsKey(watcher);
    }

    public int watcherCount(UUID target) {
        Map<UUID, Sender> senders = watchers.get(target);
        return senders == null ? 0 : senders.size();
    }

    /**
     * Throttled publish. The supplier runs only when a line is actually due, so formatting cost is
     * bounded by the interval rather than by the packet rate.
     */
    public boolean publish(UUID target, long nowNanos, long lastSentNanos, Supplier<String> line) {
        Map<UUID, Sender> senders = watchers.get(target);
        if (senders == null || senders.isEmpty()) return false;
        if (lastSentNanos != 0 && nowNanos - lastSentNanos < intervalNanos) return false;
        String text = line.get();
        if (text == null) return false;
        for (Sender sender : senders.values()) {
            if (sender.isValid()) NeuralMessages.send(sender, text);
            else senders.remove(sender.getUniqueId());
        }
        return true;
    }
}
