package dev.aeroac.neural;

import dev.aeroac.neural.dataset.SnapshotJson;
import dev.aeroac.neural.inference.ModelKind;
import dev.aeroac.neural.inference.PredictionResult;
import dev.aeroac.neural.risk.Evidence;
import dev.aeroac.neural.risk.EvidenceSnapshot;
import dev.aeroac.neural.risk.EvidenceType;
import dev.aeroac.neural.risk.PendingSnapshot;
import dev.aeroac.neural.risk.RiskState;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceSnapshotTest {
    @Test void theTailFillsSampleBySampleAndThenCompletes() {
        PendingSnapshot pending = pending(3);
        assertFalse(pending.complete());
        assertFalse(pending.accept(TelemetryFoundationTest.frame(10, false, false)));
        assertFalse(pending.accept(TelemetryFoundationTest.frame(11, false, false)));
        assertTrue(pending.accept(TelemetryFoundationTest.frame(12, false, false)));
        assertTrue(pending.complete());
        EvidenceSnapshot snapshot = pending.build("pseudonym");
        assertEquals(3, snapshot.framesAfter().length);
        assertEquals(2, snapshot.framesBefore().length);
        assertEquals(5, snapshot.frameCount());
    }

    @Test void aSegmentBoundaryEndsTheTailInsteadOfStitchingAcrossIt() {
        PendingSnapshot pending = pending(4);
        assertFalse(pending.accept(TelemetryFoundationTest.frame(10, false, false)));
        // A teleport or respawn starts a new segment; those samples do not describe this event.
        assertTrue(pending.accept(TelemetryFoundationTest.frame(11, false, true)));
        EvidenceSnapshot snapshot = pending.build("pseudonym");
        assertEquals(1, snapshot.framesAfter().length);
        assertEquals(10, snapshot.framesAfter()[0].tick());
    }

    @Test void aZeroLengthTailIsAlreadyComplete() {
        assertTrue(pending(0).complete());
    }

    @Test void aSnapshotIsAlwaysWrittenAsUnlabelledProductionData() throws Exception {
        PendingSnapshot pending = pending(1);
        pending.accept(TelemetryFoundationTest.frame(10, true, false));
        StringWriter output = new StringWriter();
        SnapshotJson.write(output, pending.build("pseudonymous-id"));
        JsonObject json = new JsonParser().parse(output.toString()).getAsJsonObject();
        assertEquals("UNLABELED", json.get("label").getAsString());
        assertEquals("PRODUCTION_UNLABELED", json.get("labelSource").getAsString());
        assertEquals("evidenceSnapshot", json.get("kind").getAsString());
        assertEquals("pseudonymous-id", json.get("playerId").getAsString());
        assertEquals(CombatFrame.SCHEMA_VERSION, json.get("schemaVersion").getAsInt());
        assertEquals("AI_AIM", json.getAsJsonObject("trigger").get("type").getAsString());
        assertEquals(0.95, json.getAsJsonObject("prediction").getAsJsonObject("heads").get("aimAssist").getAsDouble(), 1e-9);
        assertEquals(3, json.getAsJsonArray("frames").size());
        assertTrue(json.getAsJsonObject("evidenceCounts").has("GRIM_REACH"));
        // Unknown measurements stay null rather than becoming a plausible zero.
        assertTrue(json.getAsJsonArray("frames").get(0).getAsJsonObject()
                .getAsJsonObject("values").get(FrameField.SUCCESSFUL_HIT.name()).isJsonNull());
    }

    @Test void aSnapshotWithoutAPredictionIsStillValid() throws Exception {
        PendingSnapshot pending = new PendingSnapshot(
                Evidence.of(EvidenceType.GRIM_WALL_HIT, 0.5, 1, "grim/WallHit"), null, 7.0, 7.5,
                RiskState.WATCH, RiskState.SUSPICIOUS, new CombatFrame[0], 0,
                new long[EvidenceType.values().length], 1, Double.NaN, Double.NaN, 47);
        StringWriter output = new StringWriter();
        SnapshotJson.write(output, pending.build("id"));
        JsonObject json = new JsonParser().parse(output.toString()).getAsJsonObject();
        assertTrue(json.get("prediction").isJsonNull());
        assertTrue(json.get("pingMs").isJsonNull());
        assertEquals(0, json.getAsJsonArray("frames").size());
    }

    @Test void malformedSnapshotsAreRejected() {
        long[] counts = new long[EvidenceType.values().length];
        Evidence evidence = Evidence.of(EvidenceType.AI_AIM, 1, 1, "flash");
        assertThrows(IllegalArgumentException.class, () -> new PendingSnapshot(null, null, 0, 0,
                RiskState.CLEAN, RiskState.WATCH, new CombatFrame[0], 1, counts, 0, 0, 0, 47));
        assertThrows(IllegalArgumentException.class, () -> new PendingSnapshot(evidence, null, 0, 0,
                RiskState.CLEAN, RiskState.WATCH, new CombatFrame[0], -1, counts, 0, 0, 0, 47));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceSnapshot(java.util.UUID.randomUUID(), "id",
                0, 0, 0, 0, RiskState.CLEAN, RiskState.WATCH, evidence, null, new CombatFrame[0], new CombatFrame[0],
                new long[2], 0, 0, 47));
    }

    private static PendingSnapshot pending(int tail) {
        CombatFrame[] before = {
                TelemetryFoundationTest.frame(8, false, false),
                TelemetryFoundationTest.frame(9, false, false),
        };
        PredictionResult prediction = new PredictionResult(5, 1, ModelKind.FLASH, "test-v1", true,
                new String[]{"overall", "aimAssist"}, new double[]{0.91, 0.95}, 14);
        long[] counts = new long[EvidenceType.values().length];
        counts[EvidenceType.AI_AIM.ordinal()] = 12;
        counts[EvidenceType.GRIM_WALL_HIT.ordinal()] = 1;
        return new PendingSnapshot(Evidence.of(EvidenceType.AI_AIM, 0.4, 1, "flash/test-v1"), prediction,
                7.4, 8.1, RiskState.SUSPICIOUS, RiskState.SUSPICIOUS, before, tail, counts, 1, 42.0, 50.5, 765);
    }
}
