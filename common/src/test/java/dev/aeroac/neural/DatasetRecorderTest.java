package dev.aeroac.neural;

import dev.aeroac.neural.dataset.*;
import dev.aeroac.neural.telemetry.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DatasetRecorderTest {
    @TempDir Path directory;

    @Test void serializesNullsAndRoundTripsRawFrame() throws Exception {
        DatasetMetadata metadata = metadata();
        CombatFrame original = TelemetryFoundationTest.frame(4, true, false);
        StringWriter output = new StringWriter();
        DatasetJson.writeRecord(output, metadata, original);
        assertTrue(output.toString().contains("\"SUCCESSFUL_HIT\":null"));
        assertFalse(output.toString().contains("NaN"));
        CombatFrame decoded = DatasetJson.readFrame(new StringReader(output.toString()), metadata.startNanos());
        assertEquals(original.tick(), decoded.tick());
        assertEquals(original.nanoTime(), decoded.nanoTime());
        for (FrameField field : FrameField.values()) assertEquals(original.value(field), decoded.value(field));
    }

    @Test void incompatibleSchemaAndMissingFeatureAreRejected() throws Exception {
        StringWriter output = new StringWriter();
        DatasetJson.writeRecord(output, metadata(), TelemetryFoundationTest.frame(1, false, true));
        JsonObject frame = new JsonParser().parse(output.toString()).getAsJsonObject();
        frame.addProperty("schemaVersion", 99);
        assertThrows(IllegalArgumentException.class, () -> DatasetJson.readFrame(new StringReader(frame.toString()), 0));
        frame.addProperty("schemaVersion", 1);
        frame.getAsJsonObject("values").remove("YAW");
        assertThrows(IllegalArgumentException.class, () -> DatasetJson.readFrame(new StringReader(frame.toString()), 0));
    }

    @Test void preservesMultipleAttackEventsAndEscapesMetadata() throws Exception {
        StringWriter output = new StringWriter();
        DatasetMetadata metadata = metadata();
        DatasetJson.writeRecord(output, metadata, new CombatEvent(1, 0, "attack", 7, false, 179, 0));
        DatasetJson.writeRecord(output, metadata, new CombatEvent(2, 0, "attack", 8, true, -179, 0));
        assertEquals(2, output.toString().lines().count());
        StringWriter header = new StringWriter();
        DatasetJson.writeMetadata(header, new DatasetSession(metadata, 1), false, metadata.startNanos());
        JsonObject parsed = new JsonParser().parse(header.toString()).getAsJsonObject();
        assertEquals("Synthetic \"test\"\nnot ground truth", parsed.get("notes").getAsString());
        assertEquals("UNLABELED", parsed.get("label").getAsString());
        assertFalse(parsed.get("complete").getAsBoolean());
    }

    @Test void queueOverflowIsBoundedVisibleAndRetainsOrder() {
        DatasetSession session = new DatasetSession(metadata(), 2);
        CombatFrame first = TelemetryFoundationTest.frame(1, false, true);
        CombatFrame second = TelemetryFoundationTest.frame(2, false, false);
        assertTrue(session.offer(first)); assertTrue(session.offer(second));
        assertFalse(session.offer(TelemetryFoundationTest.frame(3, false, false)));
        assertEquals(1, session.dropped()); assertEquals(2, session.queued());
        session.close("MANUAL_STOP");
        assertFalse(session.offer(first)); assertFalse(session.readyToFinish());
        assertSame(first, session.poll()); assertSame(second, session.poll());
        assertNull(session.poll()); assertTrue(session.readyToFinish());
    }

    @Test void producerAndConsumerKeepOrderUnderPressureAndClose() throws Exception {
        DatasetSession session = new DatasetSession(metadata(), 64);
        AtomicLong accepted = new AtomicLong();
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try {
            Future<?> producing = producer.submit(() -> {
                for (int i = 0; i < 100_000; i++) {
                    if (session.offer(new CombatEvent(i, 0, "attack", 7, false, 0, 0))) accepted.incrementAndGet();
                }
                session.close("TEST_STOP");
            });
            long last = -1, consumed = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!session.readyToFinish()) {
                TelemetryRecord record = session.poll();
                if (record != null) { assertTrue(record.nanoTime() > last); last = record.nanoTime(); consumed++; }
                else Thread.yield();
                assertTrue(System.nanoTime() < deadline, "Queue stalled");
            }
            producing.get(5, TimeUnit.SECONDS);
            assertEquals(accepted.get(), consumed);
            assertEquals(100_000, accepted.get() + session.dropped());
        } finally { producer.shutdownNow(); }
    }

    @Test void diskWriterFlushesAndCompletesMetadataOnStop() throws Exception {
        try (DatasetManager manager = new DatasetManager(directory, settings(), message -> fail(message))) {
            DatasetSession session = manager.open(metadata()).get(2, TimeUnit.SECONDS);
            for (int i = 0; i < 32; i++) session.offer(TelemetryFoundationTest.frame(i, i == 20, i == 0));
            session.close("MANUAL_STOP");
            awaitComplete(session);
            Path raw = directory.resolve("raw/session-" + session.metadata.sessionId() + ".jsonl");
            try (var lines = Files.lines(raw)) { assertEquals(32, lines.count()); }
            JsonObject metadata = readMetadata(session);
            assertTrue(metadata.get("complete").getAsBoolean());
            assertEquals(32, metadata.get("frames").getAsInt());
            assertEquals("MANUAL_STOP", metadata.get("closeReason").getAsString());
            assertEquals(0, metadata.get("droppedRecords").getAsInt());
        }
    }

    @Test void reloadAndShutdownDrainWithoutReclassifying() throws Exception {
        DatasetSession session;
        try (DatasetManager manager = new DatasetManager(directory, settings(), message -> fail(message))) {
            session = manager.open(metadata()).get(2, TimeUnit.SECONDS);
            session.offer(TelemetryFoundationTest.frame(1, true, true));
            manager.update(settings());
            assertFalse(session.accepting());
        }
        assertTrue(session.completed());
        assertEquals("UNLABELED", readMetadata(session).get("label").getAsString());
        assertEquals("CONFIG_RELOAD", readMetadata(session).get("closeReason").getAsString());
    }

    @Test void admissionIsBoundedAndPseudonymSurvivesRestart() throws Exception {
        UUID player = UUID.randomUUID();
        String pseudonym;
        try (DatasetManager manager = new DatasetManager(directory, settings(), message -> fail(message))) {
            pseudonym = manager.pseudonym(player);
            assertEquals(64, pseudonym.length());
            assertFalse(pseudonym.contains(player.toString()));
            manager.open(metadata()).get(2, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> manager.open(metadata()).get(2, TimeUnit.SECONDS));
        }
        try (DatasetManager restarted = new DatasetManager(directory, settings(), message -> fail(message))) {
            assertEquals(pseudonym, restarted.pseudonym(player));
            assertNotEquals(pseudonym, restarted.pseudonym(UUID.randomUUID()));
        }
    }

    @Test void fileOpenFailureDoesNotLeakAdmissionSlot() throws Exception {
        DatasetMetadata metadata = metadata();
        try (DatasetManager manager = new DatasetManager(directory, settings(), message -> { })) {
            Files.createDirectory(directory.resolve("metadata/session-" + metadata.sessionId() + ".json.tmp"));
            assertThrows(ExecutionException.class, () -> manager.open(metadata).get(2, TimeUnit.SECONDS));
            assertTrue(manager.sessions().isEmpty());
            DatasetSession next = manager.open(metadata()).get(2, TimeUnit.SECONDS);
            next.close("TEST_STOP");
            awaitComplete(next);
        }
    }

    @Test void cheatLabelsRequireFamilyAndCompatibleSource() {
        DatasetMetadata base = metadata();
        assertThrows(IllegalArgumentException.class, () -> new DatasetMetadata(base.sessionId(), "p", 0, 0,
                DatasetMetadata.Label.CHEAT, DatasetMetadata.LabelSource.LAB_CHEAT, "", "", "", null,
                DatasetMetadata.AssistStrength.LOW, "", 47, "test", 96, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new DatasetMetadata(base.sessionId(), "p", 0, 0,
                DatasetMetadata.Label.CHEAT, DatasetMetadata.LabelSource.PRODUCTION_UNLABELED, "aimassist", "", "", null,
                DatasetMetadata.AssistStrength.LOW, "", 47, "test", 96, 20, 10));
    }

    private JsonObject readMetadata(DatasetSession session) throws Exception {
        return new JsonParser().parse(Files.readString(directory.resolve("metadata/session-" + session.metadata.sessionId() + ".json"))).getAsJsonObject();
    }

    private static void awaitComplete(DatasetSession session) {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (!session.completed()) Thread.sleep(10);
        });
    }

    static NeuralConfig settings() {
        return NeuralConfig.read(NeuralConfigTest.config(java.util.Map.of("neural.enabled", true,
                "neural.collection.enabled", true, "neural.collection.max-sessions", 1,
                "neural.collection.max-session-mib", 1, "neural.collection.max-total-mib", 16)));
    }

    static DatasetMetadata metadata() {
        return new DatasetMetadata(UUID.randomUUID(), "pseudonymous-test-player", System.currentTimeMillis(), System.nanoTime(),
                DatasetMetadata.Label.UNLABELED, DatasetMetadata.LabelSource.PRODUCTION_UNLABELED,
                "", "synthetic", "fixture", "unit-test", DatasetMetadata.AssistStrength.UNKNOWN,
                "Synthetic \"test\"\nnot ground truth", 47, "AeroAC-test", 96, 20, 10);
    }
}
