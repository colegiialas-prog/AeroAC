package dev.aeroac.neural.dataset;

import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.neural.NeuralConfig;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start/stop contract of the recording storage: an acknowledgement means the session really can
 * receive records, a stop means every admitted session was drained and completed, and both say which
 * of the two it was instead of sharing one message.
 */
class DatasetManagerLifecycleTest {

    private static ConfigManager config(Map<String, Object> values) {
        return (ConfigManager) Proxy.newProxyInstance(DatasetManagerLifecycleTest.class.getClassLoader(),
                new Class<?>[]{ConfigManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasLoaded")) return true;
                    if (args != null && args.length == 2) {
                        Object override = values.get(String.valueOf(args[0]));
                        if (override != null) return override;
                        return args[1];
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0d;
                    return null;
                });
    }

    private static NeuralConfig recordingConfig(int maxSessions) {
        return NeuralConfig.read(config(Map.of(
                "neural.enabled", true,
                "neural.collection.enabled", true,
                "neural.collection.max-sessions", maxSessions,
                "neural.collection.queue-capacity", 64,
                "neural.windows.attack-before", 4,
                "neural.windows.attack-after", 4,
                "neural.windows.continuous-size", 16)));
    }

    private static NeuralConfig collectionDisabledConfig() {
        return NeuralConfig.read(config(Map.of("neural.enabled", true, "neural.collection.enabled", false)));
    }

    private static DatasetMetadata metadata() {
        return new DatasetMetadata(UUID.randomUUID(), "pseudonym-" + UUID.randomUUID(), System.currentTimeMillis(),
                System.nanoTime(), DatasetMetadata.Label.UNLABELED,
                DatasetMetadata.LabelSource.PRODUCTION_UNLABELED, "", "", "test", "test", null, "", 47,
                "test", 16, 4, 4);
    }

    private static DatasetManager manager(Path root, NeuralConfig settings) throws Exception {
        List<String> problems = new ArrayList<>();
        return new DatasetManager(root, settings, problems::add);
    }

    @Test
    void anAcknowledgementMeansTheRawFileTheMetadataAndTheWriterAllExist(@TempDir Path root) throws Exception {
        DatasetManager manager = manager(root, recordingConfig(4));
        try {
            DatasetMetadata metadata = metadata();
            DatasetSession session = manager.open(metadata).get(10, TimeUnit.SECONDS);

            assertTrue(session.accepting(), "the session is registered and accepts records");
            assertTrue(Files.exists(root.resolve("raw/session-" + metadata.sessionId() + ".jsonl")));
            assertTrue(Files.exists(root.resolve("metadata/session-" + metadata.sessionId() + ".json")),
                    "the metadata file is written before the acknowledgement, not after it");
            assertEquals(1, manager.sessions().size());
        } finally {
            manager.close();
        }
    }

    @Test
    void openingIsRefusedWhenCollectionIsOffAndSaysWhichSwitch(@TempDir Path root) throws Exception {
        DatasetManager manager = manager(root, collectionDisabledConfig());
        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> manager.open(metadata()).get(10, TimeUnit.SECONDS));
            assertTrue(String.valueOf(failure.getCause().getMessage()).contains("Запись выключена"),
                    failure.getCause().getMessage());
            assertTrue(manager.sessions().isEmpty());
        } finally {
            manager.close();
        }
    }

    @Test
    void theSessionLimitIsRefusedBeforeTheDiskIsTouched(@TempDir Path root) throws Exception {
        DatasetManager manager = manager(root, recordingConfig(1));
        try {
            manager.open(metadata()).get(10, TimeUnit.SECONDS);

            ExecutionException limit = assertThrows(ExecutionException.class,
                    () -> manager.open(metadata()).get(10, TimeUnit.SECONDS));
            assertTrue(String.valueOf(limit.getCause().getMessage()).contains("предел"),
                    limit.getCause().getMessage());
        } finally {
            manager.close();
        }
    }

    @Test
    void aDuplicateSessionIdentifierIsRefused(@TempDir Path root) throws Exception {
        DatasetManager manager = manager(root, recordingConfig(4));
        try {
            DatasetMetadata first = metadata();
            manager.open(first).get(10, TimeUnit.SECONDS);

            ExecutionException duplicate = assertThrows(ExecutionException.class,
                    () -> manager.open(first).get(10, TimeUnit.SECONDS));
            assertTrue(String.valueOf(duplicate.getCause().getMessage()).contains("идентификатор"),
                    duplicate.getCause().getMessage());
            assertFalse(Files.exists(root.resolve("metadata/session-" + first.sessionId() + ".tmp")),
                    "no half-written metadata is left behind");
        } finally {
            manager.close();
        }
    }

    @Test
    void aStopDrainsEveryAdmittedSessionAndWritesItsFinalMetadata(@TempDir Path root) throws Exception {
        DatasetManager manager = manager(root, recordingConfig(4));
        DatasetMetadata metadata = metadata();
        DatasetSession session = manager.open(metadata).get(10, TimeUnit.SECONDS);

        double[] values = new double[FrameField.COUNT];
        int records = 8;
        for (int tick = 0; tick < records; tick++) {
            assertTrue(session.offer(new CombatFrame(tick, System.nanoTime() + tick, values)),
                    "the records are queued before the stop");
        }

        manager.drainAndClose();

        assertTrue(session.completed(), "the session was completed, not abandoned");
        assertTrue(manager.sessions().isEmpty(), "the drained session is no longer admitted");
        assertTrue(manager.closed());
        assertTrue(Files.size(root.resolve("raw/session-" + metadata.sessionId() + ".jsonl")) > 0,
                "the queued records reached the disk before the worker went away");

        String finalMetadata = Files.readString(root.resolve("metadata/session-" + metadata.sessionId() + ".json"));
        assertTrue(finalMetadata.contains("\"complete\": true"),
                "metadata must describe a finished recording, not one cut short: " + finalMetadata);
        assertTrue(finalMetadata.contains("\"closeReason\": \"PLUGIN_STOP\""));
        assertTrue(finalMetadata.contains("\"records\": " + records));

        ExecutionException afterStop = assertThrows(ExecutionException.class,
                () -> manager.open(metadata()).get(10, TimeUnit.SECONDS));
        assertTrue(String.valueOf(afterStop.getCause().getMessage()).contains("остановлено"),
                afterStop.getCause().getMessage());
    }

    @Test
    void aReloadClosesExistingSessionsWithTheirOwnReason(@TempDir Path root) throws Exception {
        DatasetManager manager = manager(root, recordingConfig(4));
        try {
            DatasetMetadata metadata = metadata();
            DatasetSession session = manager.open(metadata).get(10, TimeUnit.SECONDS);

            manager.update(recordingConfig(4));

            assertFalse(session.accepting(), "a reload must not let a session span two configurations");
            assertEquals("CONFIG_RELOAD", session.closeReason());
        } finally {
            manager.close();
        }
    }
}
