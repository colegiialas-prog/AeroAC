package dev.aeroac.platform.bukkit.admin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aeroac.utils.anticheat.LogUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A moderator's own conclusion about a player, kept across restarts in neural/staff-marks.json.
 *
 * <p>It is a note between staff and nothing else: it never moves risk, never trains a model and never
 * bans. A "checked, clean" mark on a player the model keeps flagging is exactly the disagreement an
 * operator wants to see next time, so both are shown side by side.
 */
public final class StaffMarks {
    public enum Mark {
        NONE, CLEAN, WATCHING, CHEATER;

        public Mark next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public record Entry(Mark mark, String by, long atMillis) { }

    private final Path file;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public StaffMarks(Path file) {
        this.file = file;
        load();
    }

    public Entry get(UUID player) { return player == null ? null : entries.get(player); }

    public Mark mark(UUID player) {
        Entry entry = get(player);
        return entry == null ? Mark.NONE : entry.mark();
    }

    /** Sets the next mark in the cycle and saves. Returns the new mark. */
    public synchronized Mark cycle(UUID player, String by) {
        Mark next = mark(player).next();
        if (next == Mark.NONE) entries.remove(player);
        else entries.put(player, new Entry(next, by, System.currentTimeMillis()));
        save();
        return next;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> item : root.entrySet()) {
                JsonObject value = item.getValue().getAsJsonObject();
                entries.put(UUID.fromString(item.getKey()), new Entry(Mark.valueOf(value.get("mark").getAsString()),
                        value.get("by").getAsString(), value.get("at").getAsLong()));
            }
        } catch (IOException | RuntimeException error) {
            LogUtil.warn("Aero staff marks not read (" + file + "): " + error.getMessage());
        }
    }

    private void save() {
        if (file == null) return;
        JsonObject root = new JsonObject();
        entries.forEach((player, entry) -> {
            JsonObject value = new JsonObject();
            value.addProperty("mark", entry.mark().name());
            value.addProperty("by", entry.by());
            value.addProperty("at", entry.atMillis());
            root.add(player.toString(), value);
        });
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException error) {
            LogUtil.warn("Aero staff marks not written (" + file + "): " + error.getMessage());
        }
    }
}
