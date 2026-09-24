package dev.aeroac.neural;

import dev.aeroac.neural.dataset.*;
import dev.aeroac.neural.telemetry.CombatEvent;
import dev.aeroac.neural.telemetry.CombatFrame;
import org.junit.jupiter.api.Test;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Generates a reviewable protocol fixture using the actual serializer. Never claims live gameplay. */
class DatasetExampleTest {
    @Test void writesSyntheticReviewSession() throws Exception {
        Path root = Path.of("build", "neural-example");
        Files.createDirectories(root.resolve("raw"));
        Files.createDirectories(root.resolve("metadata"));
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000001");
        DatasetMetadata metadata = new DatasetMetadata(id, "synthetic-player-001", 0, 0,
                DatasetMetadata.Label.UNLABELED, DatasetMetadata.LabelSource.PRODUCTION_UNLABELED,
                "", "synthetic", "serializer-fixture", "serializer-fixture", DatasetMetadata.AssistStrength.UNKNOWN,
                "SYNTHETIC EXAMPLE ONLY. Not recorded gameplay; excluded from training and evaluation.",
                47, "AeroAC-phase1-fixture", 96, 20, 10);
        DatasetSession session = new DatasetSession(metadata, 64);
        Path raw = root.resolve("raw/session-" + id + ".jsonl");
        try (Writer output = Files.newBufferedWriter(raw)) {
            for (int tick = 0; tick <= 32; tick++) {
                if (tick == 20) {
                    DatasetJson.writeRecord(output, metadata, new CombatEvent(tick * 50_000_000L - 1, tick - 1, "attack", 42, false, 38, 0));
                    session.written(false);
                }
                CombatFrame frame = TelemetryFoundationTest.frame(tick, tick == 20, tick == 0);
                DatasetJson.writeRecord(output, metadata, frame);
                session.written(true);
            }
        }
        session.close("SYNTHETIC_EXAMPLE");
        try (Writer output = Files.newBufferedWriter(root.resolve("metadata/session-" + id + ".json"))) {
            DatasetJson.writeMetadata(output, session, true, 1_600_000_000L);
        }
        try (var lines = Files.lines(raw)) { assertEquals(34, lines.count()); }
    }
}
