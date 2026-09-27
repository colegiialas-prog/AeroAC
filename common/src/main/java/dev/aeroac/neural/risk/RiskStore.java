package dev.aeroac.neural.risk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Risk that outlives the connection and the server process: one number and a wall-clock time per
 * player, in a small JSON file under the plugin folder. It exists so that leaving, a reload or a
 * restart is not a free reset. Evidence behind the number is not kept, which is why a restored value
 * is capped by the caller and decays for the time the player was away.
 *
 * <p>Writes are coalesced onto one daemon thread and replace the file atomically; a failed write is
 * logged and retried with the next change, never surfaced to the packet path.
 */
public final class RiskStore implements AutoCloseable {
    private static final int FORMAT = 1;
    private static final int MAX_ENTRIES = 100_000;

    public record Stored(double risk, long savedAtMillis) { }

    private final Path file;
    private final Consumer<String> warn;
    private final ConcurrentHashMap<UUID, Stored> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Aero-neural-risk-store");
        thread.setDaemon(true);
        return thread;
    });

    public RiskStore(Path file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn == null ? message -> { } : warn;
        load();
    }

    public int size() { return entries.size(); }

    /** Remembers a positive risk; a clean player is forgotten rather than stored as zero. */
    public void put(UUID player, double risk, long nowMillis) {
        if (player == null) return;
        if (!(risk > 0) || !Double.isFinite(risk)) {
            if (entries.remove(player) != null) scheduleSave();
            return;
        }
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(player)) return;
        entries.put(player, new Stored(risk, nowMillis));
        scheduleSave();
    }

    /** Removes and returns the player's stored risk, or null. */
    public Stored take(UUID player) {
        Stored stored = player == null ? null : entries.remove(player);
        if (stored != null) scheduleSave();
        return stored;
    }

    /** Drops entries older than the given age. */
    public void prune(long nowMillis, long maxAgeMillis) {
        if (entries.entrySet().removeIf(entry -> nowMillis - entry.getValue().savedAtMillis() > maxAgeMillis
                || entry.getValue().savedAtMillis() > nowMillis + 60_000L)) {
            scheduleSave();
        }
    }

    private void scheduleSave() {
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            writer.execute(() -> {
                scheduled.set(false);
                save();
            });
        } catch (RuntimeException closed) {
            scheduled.set(false);
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("format") == null || root.get("format").getAsInt() != FORMAT) {
                warn.accept("Aero AC: неизвестный формат " + file + ", сохранённый риск не загружен");
                return;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("players").entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                double risk = value.get("risk").getAsDouble();
                long saved = value.get("savedAt").getAsLong();
                if (risk > 0 && Double.isFinite(risk)) entries.put(UUID.fromString(entry.getKey()), new Stored(risk, saved));
            }
        } catch (IOException | RuntimeException error) {
            warn.accept("Aero AC: сохранённый риск не прочитан (" + file + "): " + error.getMessage());
        }
    }

    private synchronized void save() {
        if (file == null) return;
        JsonObject players = new JsonObject();
        entries.forEach((player, stored) -> {
            JsonObject value = new JsonObject();
            value.addProperty("risk", stored.risk());
            value.addProperty("savedAt", stored.savedAtMillis());
            players.add(player.toString(), value);
        });
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("players", players);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            warn.accept("Aero AC: сохранённый риск не записан (" + file + "): " + error.getMessage());
        }
    }

    /** Writes what is pending and stops the writer, waiting at most the given time. */
    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(1, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException interrupted) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        save();
    }
}
