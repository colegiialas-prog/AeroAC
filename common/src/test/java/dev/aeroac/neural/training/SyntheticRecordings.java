package dev.aeroac.neural.training;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Writes small recordings in the plugin's dataset layout (metadata/ + raw/) for trainer tests. A
 * toy aim controller, not a client: the "cheat" closes its aim error with a near-constant gain and
 * almost no jitter, the "legit" player corrects late, overshoots and shakes.
 */
final class SyntheticRecordings {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private SyntheticRecordings() { }

    static void write(Path root, int players, int frames, long seed) throws IOException {
        Files.createDirectories(root.resolve("metadata"));
        Files.createDirectories(root.resolve("raw"));
        Random random = new Random(seed);
        for (int player = 0; player < players; player++) {
            for (boolean cheat : new boolean[]{false, true}) {
                String sessionId = String.format("p%02d-%s", player, cheat ? "cheat" : "legit");
                String client = cheat ? (player % 2 == 0 ? "clientA" : "clientB") : "vanilla";
                session(root, sessionId, "synthetic-player-" + player, cheat, client, frames, random);
            }
        }
    }

    /** One operator recording themselves: every session is the same player and the same cheat client. */
    static void writeSolo(Path root, int sessionsPerLabel, int frames, long seed) throws IOException {
        Files.createDirectories(root.resolve("metadata"));
        Files.createDirectories(root.resolve("raw"));
        Random random = new Random(seed);
        for (int index = 0; index < sessionsPerLabel; index++) {
            session(root, String.format("solo-%02d-legit", index), "solo-admin", false, "vanilla", frames, random);
            session(root, String.format("solo-%02d-cheat", index), "solo-admin", true, "unspecified", frames, random);
        }
    }

    private static void session(Path root, String sessionId, String playerId, boolean cheat, String client, int frames,
                                Random random) throws IOException {
        try (Writer raw = Files.newBufferedWriter(root.resolve("raw/session-" + sessionId + ".jsonl"), StandardCharsets.UTF_8)) {
            double targetYaw = 0, errorYaw = 20, errorPitch = 5, previousYaw = Double.NaN, previousPitch = Double.NaN;
            for (int tick = 0; tick < frames; tick++) {
                targetYaw += random.nextGaussian() * 3;
                if (tick % 40 == 0) errorYaw = (random.nextBoolean() ? 1 : -1) * (10 + random.nextDouble() * 25);
                double gain = cheat ? 0.35 : 0.15 + random.nextDouble() * 0.5;
                double jitter = cheat ? 0.05 : 1.2;
                errorYaw = errorYaw * (1 - gain) + random.nextGaussian() * jitter;
                errorPitch = errorPitch * (1 - gain) + random.nextGaussian() * jitter * 0.5;
                double yaw = targetYaw - errorYaw, pitch = 10 - errorPitch;
                Map<String, Object> values = new LinkedHashMap<>();
                for (FrameField field : FrameField.values()) if (field.since() <= CombatFrame.SCHEMA_VERSION) values.put(field.name(), null);
                values.put("YAW", yaw);
                values.put("PITCH", pitch);
                values.put("DELTA_YAW", Double.isNaN(previousYaw) ? null : yaw - previousYaw);
                values.put("DELTA_PITCH", Double.isNaN(previousPitch) ? null : pitch - previousPitch);
                values.put("AIM_ERROR_YAW", errorYaw);
                values.put("AIM_ERROR_PITCH", errorPitch);
                values.put("AIM_ERROR_TOTAL", Math.hypot(errorYaw, errorPitch));
                values.put("TARGET_PRESENT", 1);
                values.put("TARGET_ENTITY_ID", 7);
                values.put("TARGET_SWITCH", 0);
                values.put("ATTACK", tick % 6 == 3 ? 1 : 0);
                values.put("SEGMENT_START", tick == 0 ? 1 : 0);
                values.put("SAMPLE_INTERVAL_MS", 50);
                previousYaw = yaw;
                previousPitch = pitch;
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("schemaVersion", CombatFrame.SCHEMA_VERSION);
                record.put("sessionId", sessionId);
                record.put("offsetNanos", tick * 50_000_000L);
                record.put("type", "frame");
                record.put("tick", 1000L + tick);
                record.put("values", values);
                raw.write(JSON.toJson(record));
                raw.write('\n');
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", CombatFrame.SCHEMA_VERSION);
        metadata.put("datasetVersion", "dataset-v1");
        metadata.put("sessionId", sessionId);
        metadata.put("playerId", playerId);
        metadata.put("startTimestamp", 0);
        metadata.put("label", cheat ? "CHEAT" : "LEGIT");
        metadata.put("labelSource", cheat ? "LAB_CHEAT" : "LAB_LEGIT");
        metadata.put("cheatFamily", cheat ? "aimassist" : null);
        metadata.put("clientFamily", client);
        metadata.put("configuration", "default");
        metadata.put("notes", "synthetic");
        metadata.put("minecraftProtocol", 765);
        metadata.put("pluginVersion", "test");
        metadata.put("continuousSize", 96);
        metadata.put("attackBefore", 20);
        metadata.put("attackAfter", 10);
        metadata.put("durationMs", frames * 50L);
        metadata.put("frames", frames);
        metadata.put("records", frames);
        metadata.put("droppedRecords", 0);
        metadata.put("complete", true);
        metadata.put("failure", null);
        Files.writeString(root.resolve("metadata/session-" + sessionId + ".json"), JSON.toJson(metadata), StandardCharsets.UTF_8);
    }
}
