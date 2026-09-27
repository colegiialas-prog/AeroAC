package dev.aeroac.neural.dataset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads evidence snapshots back for an operator: which events fired for a player, and what the
 * player's aim did around each of them. Snapshots name the player only by pseudonym, so the caller
 * passes the pseudonym (DatasetManager.pseudonym) and nothing here ever sees a UUID or a name.
 *
 * <p>Disk work: call it off the server thread. Files are bounded in size and count, and a file that
 * does not parse is skipped rather than failing the list.
 */
public final class SnapshotIndex {
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /** One row of the list. */
    public record Summary(String file, String eventId, long timestampMillis, double riskBefore, double riskAfter,
                          String stateBefore, String stateAfter, String triggerType, double triggerStrength,
                          String triggerSource, String model, double overall, int frames) { }

    /** One sample of the replay, only the values an operator can read at a glance. */
    public record Sample(long offsetMs, double deltaYaw, double deltaPitch, double aimError, double distance,
                         boolean targetPresent, boolean onTarget, boolean attack, boolean afterEvent) { }

    public record Detail(Summary summary, Map<String, Double> heads, Map<String, Long> evidenceCounts,
                         double pingMs, int protocol, List<Sample> samples) { }

    private SnapshotIndex() { }

    /** The player's snapshots, newest first, at most limit of them. */
    public static List<Summary> list(Path directory, String pseudonym, int limit) {
        List<Summary> result = new ArrayList<>();
        if (directory == null || pseudonym == null || !Files.isDirectory(directory)) return result;
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException error) {
            return result;
        }
        for (Path file : files) {
            if (result.size() >= limit) break;
            JsonObject root = read(file);
            if (root == null || !pseudonym.equals(text(root, "playerId"))) continue;
            result.add(summary(file, root));
        }
        return result;
    }

    /** Everything the detail screen shows, or null when the file is gone or unreadable. */
    public static Detail detail(Path directory, String file) {
        if (directory == null || file == null || file.contains("/") || file.contains("\\") || file.contains("..")) return null;
        Path path = directory.resolve(file);
        JsonObject root = read(path);
        if (root == null) return null;
        Map<String, Double> heads = new LinkedHashMap<>();
        JsonElement prediction = root.get("prediction");
        if (prediction != null && prediction.isJsonObject() && prediction.getAsJsonObject().has("heads")) {
            for (Map.Entry<String, JsonElement> head : prediction.getAsJsonObject().getAsJsonObject("heads").entrySet()) {
                heads.put(head.getKey(), number(head.getValue()));
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        if (root.has("evidenceCounts")) {
            for (Map.Entry<String, JsonElement> count : root.getAsJsonObject("evidenceCounts").entrySet()) {
                if (count.getValue().getAsLong() > 0) counts.put(count.getKey(), count.getValue().getAsLong());
            }
        }
        int before = root.has("framesBefore") ? root.get("framesBefore").getAsInt() : 0;
        List<Sample> samples = new ArrayList<>();
        if (root.has("frames")) {
            int index = 0;
            for (JsonElement element : root.getAsJsonArray("frames")) {
                JsonObject frame = element.getAsJsonObject();
                JsonObject values = frame.getAsJsonObject("values");
                double onTarget = crosshair(values);
                samples.add(new Sample(frame.get("offsetNanos").getAsLong() / 1_000_000L,
                        field(values, "DELTA_YAW"), field(values, "DELTA_PITCH"), field(values, "AIM_ERROR_TOTAL"),
                        field(values, "DISTANCE_TO_TARGET"), field(values, "TARGET_PRESENT") == 1, onTarget == 1,
                        field(values, "ATTACK") == 1, index >= before));
                index++;
            }
        }
        return new Detail(summary(path, root), heads, counts, number(root.get("pingMs")),
                root.has("minecraftProtocol") ? root.get("minecraftProtocol").getAsInt() : -1, samples);
    }

    /** Whether the look ray entered the target box in this frame: 1, 0, or NaN when unknown. */
    static double crosshair(JsonObject values) {
        double yaw = Math.toRadians(field(values, "YAW")), pitch = Math.toRadians(field(values, "PITCH"));
        double[] origin = {field(values, "PLAYER_X"), field(values, "PLAYER_Y") + field(values, "EYE_HEIGHT"), field(values, "PLAYER_Z")};
        double[] direction = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] low = {field(values, "TARGET_MIN_X"), field(values, "TARGET_MIN_Y"), field(values, "TARGET_MIN_Z")};
        double[] high = {field(values, "TARGET_MAX_X"), field(values, "TARGET_MAX_Y"), field(values, "TARGET_MAX_Z")};
        if (field(values, "TARGET_PRESENT") != 1) return Double.NaN;
        double near = Double.NEGATIVE_INFINITY, far = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(origin[axis] + direction[axis] + low[axis] + high[axis])) return Double.NaN;
            if (Math.abs(direction[axis]) < 1.0E-9) {
                if (origin[axis] < low[axis] || origin[axis] > high[axis]) return 0;
                continue;
            }
            double first = (low[axis] - origin[axis]) / direction[axis], second = (high[axis] - origin[axis]) / direction[axis];
            near = Math.max(near, Math.min(first, second));
            far = Math.min(far, Math.max(first, second));
        }
        return Math.max(near, 0) <= far ? 1 : 0;
    }

    private static Summary summary(Path file, JsonObject root) {
        JsonObject trigger = root.has("trigger") ? root.getAsJsonObject("trigger") : new JsonObject();
        JsonElement prediction = root.get("prediction");
        String model = null;
        double overall = Double.NaN;
        if (prediction != null && prediction.isJsonObject()) {
            JsonObject object = prediction.getAsJsonObject();
            model = text(object, "model") + " " + text(object, "modelVersion");
            if (object.has("heads")) overall = number(object.getAsJsonObject("heads").get("overall"));
        }
        int frames = (root.has("framesBefore") ? root.get("framesBefore").getAsInt() : 0)
                + (root.has("framesAfter") ? root.get("framesAfter").getAsInt() : 0);
        return new Summary(file.getFileName().toString(), text(root, "eventId"), root.get("timestamp").getAsLong(),
                number(root.get("riskBefore")), number(root.get("riskAfter")), text(root, "stateBefore"),
                text(root, "stateAfter"), text(trigger, "type"), number(trigger.get("strength")),
                text(trigger, "source"), model, overall, frames);
    }

    private static JsonObject read(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) return null;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return "evidenceSnapshot".equals(text(root, "kind")) && root.has("timestamp") ? root : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static double field(JsonObject values, String name) {
        return values == null ? Double.NaN : number(values.get(name));
    }

    private static double number(JsonElement element) {
        return element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber() ? Double.NaN : element.getAsDouble();
    }

    private static String text(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
