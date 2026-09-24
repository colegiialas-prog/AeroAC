package dev.aeroac.neural.admin.training;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ReportImportTest {
    private static DatasetSummary await(DatasetSummaryService service) throws Exception {
        for (int i = 0; i < 100; i++) {
            DatasetSummary summary = service.snapshot();
            if (summary.ready()) return summary;
            Thread.sleep(20);
        }
        fail("summary did not complete"); return null;
    }
    private static Path session(Path root, String id) throws Exception {
        Path metadata = Files.createDirectories(root.resolve("metadata")).resolve("session-" + id + ".json");
        Files.writeString(metadata, "{\"sessionId\":\"" + id + "\",\"label\":\"LEGIT\",\"playerId\":\"P\","
                + "\"startTimestamp\":1000,\"minecraftProtocol\":47,\"durationMs\":60000,\"frames\":1200,"
                + "\"records\":1200,\"droppedRecords\":0,\"complete\":true}");
        Files.writeString(Files.createDirectories(root.resolve("raw")).resolve("session-" + id + ".jsonl"), "fixture, not telemetry");
        return metadata;
    }
    @Test void boundAuditImportsMetricsAndInvalidatesWhenSourceChanges(@TempDir Path root) throws Exception {
        Path meta = session(root, "a"), raw = root.resolve("raw/session-a.jsonl");
        JsonObject row = new JsonObject();
        row.addProperty("sessionId", "a"); row.addProperty("label", "LEGIT"); row.addProperty("verdict", "REVIEW");
        row.addProperty("metadataSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(meta))));
        row.addProperty("rawBytes", Files.size(raw));
        row.addProperty("rawModifiedNs", Long.toString(Files.getLastModifiedTime(raw).to(TimeUnit.NANOSECONDS)));
        row.addProperty("attackWindows", 40);
        JsonObject metrics = new JsonObject(); metrics.addProperty("combatSeconds", 12); metrics.addProperty("medianPingMs", 180);
        row.add("metrics", metrics);
        JsonArray rows = new JsonArray(); rows.add(row);
        JsonObject report = new JsonObject(); report.addProperty("adminImportVersion", 1); report.add("sessions", rows);
        Files.writeString(Files.createDirectories(root.resolve("audit")).resolve("latest.json"), report.toString());
        try (var service = new DatasetSummaryService(root, 60, 100, 20)) {
            DatasetSummary summary = await(service);
            assertEquals(1, summary.audited());
            assertEquals(0, summary.reviewed());
            assertEquals(40, summary.audit().attackWindows());
            assertEquals(12, summary.audit().combatSeconds());
            assertEquals(1, summary.audit().highPingLegitSessions());
            Files.writeString(raw, "changed fixture");
            service.invalidate(); summary = await(service);
            assertEquals(0, summary.audited());
            assertFalse(summary.audit().present());
        }
    }
    @Test void corruptMetadataStaysVisibleAndCapSelectsRecentFiles(@TempDir Path root) throws Exception {
        Path old = session(root, "z"), recent = session(root, "a");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(1000));
        Files.setLastModifiedTime(recent, java.nio.file.attribute.FileTime.fromMillis(2000));
        try (var service = new DatasetSummaryService(root, 60, 1, 20)) {
            var summary = await(service);
            assertTrue(summary.truncated());
            assertEquals("a", summary.recent().get(0).sessionId(), "UUID lexical order is not chronological");
        }
        Files.writeString(recent, "{broken");
        try (var service = new DatasetSummaryService(root, 60, 100, 20)) {
            var summary = await(service);
            assertEquals(2, summary.sessions()); assertEquals(1, summary.failed());
            assertEquals("a", summary.attention().get(0).sessionId());
        }
    }
    @Test void reportClientIsAsynchronousOfflineAndRejectsInvalidMetrics(@TempDir Path root) throws Exception {
        try (var client = new ReportDirectoryClient(root)) {
            assertTrue(client.configured()); client.poll();
            assertFalse(client.currentEvaluation().present());
            assertEquals(TrainingJob.Status.DISCONNECTED, client.job().status());
        }
        Path path = root.resolve("current.json");
        String data = "{\"reportVersion\":1,\"producedAtMillis\":1,\"cohortId\":\"" + "a".repeat(64)
                + "\",\"modelVersion\":\"fixture\",\"featureSchemaVersion\":2,"
                + "\"calibration\":\"UNKNOWN\",\"population\":\"MIXED\",\"falsePositivesPerCombatHour\":0}";
        Files.writeString(path, data);
        EvaluationSummary result = ReportDirectoryClient.readEvaluation(path);
        assertTrue(result.present()); assertEquals(0, result.falsePositivesPerCombatHour());
        assertTrue(Double.isNaN(result.tprAtFpr()));
        Files.writeString(path, data.replace("\"falsePositivesPerCombatHour\":0", "\"falsePositivesPerCombatHour\":-1"));
        assertThrows(IllegalArgumentException.class, () -> ReportDirectoryClient.readEvaluation(path));
        var unbound = new EvaluationSummary("fixture", "same-name", 2, 1, 1, 0.001, 1, 0, 0,
                EvaluationSummary.Calibration.UNKNOWN, EvaluationSummary.Population.MIXED,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.List.of(), 1);
        assertTrue(ModelComparison.of(unbound, unbound).unmeasured(), "matching dataset names do not prove identical cohorts");
    }
}
