package dev.aeroac.neural.admin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded by online connections. Invalidated callbacks cannot republish after quit or reload. */
public final class SnapshotMailbox<T> {
    public record Ticket(UUID player, Object connection, long generation) { }
    private record Value<T>(T view, long time) { }
    private final Map<UUID, Ticket> pending = new HashMap<>();
    private final Map<UUID, Value<T>> values = new HashMap<>();
    private long generation;

    public synchronized long generation() { return generation; }

    public synchronized Ticket begin(UUID player, Object connection) {
        if (pending.containsKey(player)) return null;
        Ticket ticket = new Ticket(player, connection, generation);
        pending.put(player, ticket);
        return ticket;
    }

    public synchronized boolean complete(Ticket ticket, T value, long now) {
        if (ticket == null || ticket.generation() != generation || pending.get(ticket.player()) != ticket) return false;
        pending.remove(ticket.player());
        if (value == null) values.remove(ticket.player());
        else values.put(ticket.player(), new Value<>(value, now));
        return true;
    }

    public synchronized T get(UUID player, long now, long maxAgeMs) {
        Value<T> value = values.get(player);
        return value == null || now < value.time() || now - value.time() > maxAgeMs ? null : value.view();
    }

    public synchronized void forget(UUID player) { pending.remove(player); values.remove(player); }

    public synchronized void reset() { generation++; pending.clear(); values.clear(); }
}
