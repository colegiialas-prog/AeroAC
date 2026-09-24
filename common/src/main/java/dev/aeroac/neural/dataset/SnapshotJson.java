package dev.aeroac.neural.dataset;

import dev.aeroac.neural.inference.PredictionResult;
import dev.aeroac.neural.risk.Evidence;
import dev.aeroac.neural.risk.EvidenceSnapshot;
import dev.aeroac.neural.risk.EvidenceType;
import dev.aeroac.neural.telemetry.CombatFrame;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Writer;

/**
 * On-disk form of an evidence snapshot. The label is always UNLABELED: a snapshot exists because a
 * model and a risk threshold fired, which is a reason to review the event, never a proven verdict.
 */
public final class SnapshotJson {
    private SnapshotJson() { }

    public static void write(Writer output, EvidenceSnapshot snapshot) throws IOException {
        JsonWriter json = new JsonWriter(output);
        json.setSerializeNulls(true);
        json.setIndent("  ");
        json.beginObject();
        json.name("schemaVersion").value(CombatFrame.SCHEMA_VERSION);
        json.name("kind").value("evidenceSnapshot");
        json.name("label").value("UNLABELED");
        json.name("labelSource").value("PRODUCTION_UNLABELED");
        json.name("eventId").value(snapshot.eventId().toString());
        json.name("playerId").value(snapshot.playerId());
        json.name("timestamp").value(snapshot.timestampMillis());
        json.name("minecraftProtocol").value(snapshot.minecraftProtocol());
        DatasetJson.number(json, "pingMs", snapshot.pingMs());
        DatasetJson.number(json, "serverTickDurationMs", snapshot.serverTickDurationMs());
        json.name("riskBefore").value(snapshot.riskBefore());
        json.name("riskAfter").value(snapshot.riskAfter());
        json.name("stateBefore").value(snapshot.stateBefore().name());
        json.name("stateAfter").value(snapshot.stateAfter().name());
        trigger(json, snapshot.trigger());
        prediction(json, snapshot.prediction());
        json.name("evidenceCounts").beginObject();
        EvidenceType[] types = EvidenceType.values();
        for (int i = 0; i < types.length; i++) json.name(types[i].name()).value(snapshot.evidenceCounts()[i]);
        json.endObject();
        json.name("framesBefore").value(snapshot.framesBefore().length);
        json.name("framesAfter").value(snapshot.framesAfter().length);
        json.name("frames").beginArray();
        for (CombatFrame frame : snapshot.framesBefore()) DatasetJson.writeFrame(json, frame, snapshot.anchorNanos());
        for (CombatFrame frame : snapshot.framesAfter()) DatasetJson.writeFrame(json, frame, snapshot.anchorNanos());
        json.endArray();
        json.endObject();
        json.flush();
        output.write('\n');
    }

    private static void trigger(JsonWriter json, Evidence evidence) throws IOException {
        json.name("trigger").beginObject();
        json.name("type").value(evidence.type().name());
        json.name("strength").value(evidence.strength());
        json.name("source").value(evidence.source());
        json.name("metadata").value(evidence.metadata());
        json.endObject();
    }

    private static void prediction(JsonWriter json, PredictionResult prediction) throws IOException {
        json.name("prediction");
        if (prediction == null) {
            json.nullValue();
            return;
        }
        json.beginObject();
        json.name("requestId").value(prediction.requestId());
        json.name("model").value(prediction.model().wireName());
        json.name("modelVersion").value(prediction.modelVersion());
        json.name("calibrated").value(prediction.calibrated());
        json.name("latencyMs").value(prediction.latencyMs());
        json.name("heads").beginObject();
        for (int i = 0; i < prediction.headNames().length; i++) {
            DatasetJson.number(json, prediction.headNames()[i], prediction.headValues()[i]);
        }
        json.endObject();
        json.endObject();
    }
}
