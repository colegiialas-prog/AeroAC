package dev.aeroac.neural;

import dev.aeroac.neural.dataset.SnapshotIndex;
import dev.aeroac.neural.dataset.SnapshotJson;
import dev.aeroac.neural.inference.ModelKind;
import dev.aeroac.neural.inference.PredictionResult;
import dev.aeroac.neural.risk.*;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;
import org.junit.jupiter.api.Test;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotIndexTest {

    private static CombatFrame frame(long tick, double yaw, boolean attack) {
        double[] values = new double[FrameField.COUNT];
        Arrays.fill(values, Double.NaN);
        values[FrameField.YAW.ordinal()] = yaw;
        values[FrameField.PITCH.ordinal()] = 0;
        values[FrameField.DELTA_YAW.ordinal()] = 2.5;
        values[FrameField.TARGET_PRESENT.ordinal()] = 1;
        values[FrameField.ATTACK.ordinal()] = attack ? 1 : 0;
        values[FrameField.AIM_ERROR_TOTAL.ordinal()] = 3.0;
        values[FrameField.PLAYER_X.ordinal()] = 0; values[FrameField.PLAYER_Y.ordinal()] = 0; values[FrameField.PLAYER_Z.ordinal()] = 0;
        values[FrameField.EYE_HEIGHT.ordinal()] = 1.62;
        values[FrameField.TARGET_MIN_X.ordinal()] = 2.7; values[FrameField.TARGET_MAX_X.ordinal()] = 3.3;
        values[FrameField.TARGET_MIN_Y.ordinal()] = 0; values[FrameField.TARGET_MAX_Y.ordinal()] = 1.8;
        values[FrameField.TARGET_MIN_Z.ordinal()] = -0.3; values[FrameField.TARGET_MAX_Z.ordinal()] = 0.3;
        return new CombatFrame(tick, tick * 50_000_000L, values);
    }

    private static void write(Path directory, String player, long timestamp) throws Exception {
        UUID event = UUID.randomUUID();
        PredictionResult prediction = new PredictionResult(1, 0, ModelKind.FLASH, "v1", true,
                new String[]{"overall", "aimAssist"}, new double[]{0.93, 0.95}, 4);
        long[] counts = new long[EvidenceType.values().length];
        counts[EvidenceType.AI_AIM.ordinal()] = 7;
        EvidenceSnapshot snapshot = new EvidenceSnapshot(event, player, timestamp, 0, 5.5, 6.2,
                RiskState.WATCH, RiskState.SUSPICIOUS, Evidence.of(EvidenceType.AI_AIM, 0.7, 0, "flash/v1"), prediction,
                new CombatFrame[]{frame(1, -90, false), frame(2, -80, false)}, new CombatFrame[]{frame(3, -90, true)},
                counts, 40, 50, 767);
        try (Writer writer = Files.newBufferedWriter(directory.resolve(timestamp + "-" + event + ".json"))) {
            SnapshotJson.write(writer, snapshot);
        }
    }

    @Test void listsOnlyThePlayersSnapshotsNewestFirstAndReadsTheReplay() throws Exception {
        Path directory = Files.createTempDirectory("aero-snapshots");
        write(directory, "alice", 1_000);
        write(directory, "alice", 3_000);
        write(directory, "bob", 2_000);
        Files.writeString(directory.resolve("9999-broken.json"), "{ nope");

        List<SnapshotIndex.Summary> list = SnapshotIndex.list(directory, "alice", 10);
        assertEquals(2, list.size());
        assertEquals(3_000, list.get(0).timestampMillis());
        assertEquals("SUSPICIOUS", list.get(0).stateAfter());
        assertEquals("AI_AIM", list.get(0).triggerType());
        assertEquals(0.93, list.get(0).overall(), 1e-12);
        assertEquals(3, list.get(0).frames());
        assertEquals(1, SnapshotIndex.list(directory, "alice", 1).size());

        SnapshotIndex.Detail detail = SnapshotIndex.detail(directory, list.get(0).file());
        assertEquals(0.95, detail.heads().get("aimAssist"), 1e-12);
        assertEquals(7L, detail.evidenceCounts().get("AI_AIM"));
        assertEquals(3, detail.samples().size());
        assertTrue(detail.samples().get(0).onTarget(), "yaw -90 looks straight at the box");
        assertFalse(detail.samples().get(1).onTarget(), "yaw -80 passes beside it");
        assertFalse(detail.samples().get(1).afterEvent());
        assertTrue(detail.samples().get(2).afterEvent() && detail.samples().get(2).attack());
        assertNull(SnapshotIndex.detail(directory, "../escape.json"), "paths never leave the directory");
    }
}
