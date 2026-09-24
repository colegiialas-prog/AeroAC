package dev.aeroac.neural.dataset;

import dev.aeroac.neural.telemetry.*;
import com.google.gson.stream.JsonWriter;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Writer;
import java.io.Reader;

/** Streaming JSON on the disk worker only; compatible with older platform Gson versions. */
public final class DatasetJson {
    /** Written into every session's metadata; the Python tooling keys its loaders off it. */
    public static final String DATASET_VERSION = "dataset-v1";

    private static final FrameField[] FIELDS = FrameField.values();
    private DatasetJson() { }

    /** Strict frame decoder for offline validation. Rejects missing/reordered-incompatible schemas. */
    public static CombatFrame readFrame(Reader input, long sessionStartNanos) {
        JsonObject json = new JsonParser().parse(input).getAsJsonObject();
        if (json.get("schemaVersion").getAsInt() != CombatFrame.SCHEMA_VERSION || !"frame".equals(json.get("type").getAsString())) {
            throw new IllegalArgumentException("Incompatible dataset schema/type");
        }
        JsonObject values = json.getAsJsonObject("values");
        if (values.entrySet().size() != FIELDS.length) throw new IllegalArgumentException("Incompatible feature count");
        double[] data = new double[FIELDS.length];
        for (FrameField field : FIELDS) {
            JsonElement value = values.get(field.name());
            if (value == null) throw new IllegalArgumentException("Missing field " + field);
            if (value.isJsonNull()) data[field.ordinal()] = Double.NaN;
            else {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Non-numeric field " + field);
                double number = value.getAsDouble();
                if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite field " + field);
                data[field.ordinal()] = number;
            }
        }
        return new CombatFrame(json.get("tick").getAsLong(), Math.addExact(sessionStartNanos, json.get("offsetNanos").getAsLong()), data);
    }

    /** Shared frame encoding so an evidence snapshot and a raw session describe a frame identically. */
    public static void writeFrame(JsonWriter json, CombatFrame frame, long anchorNanos) throws IOException {
        json.beginObject();
        json.name("tick").value(frame.tick());
        json.name("offsetNanos").value(frame.nanoTime() - anchorNanos);
        json.name("values").beginObject();
        for (FrameField field : FIELDS) number(json, field.name(), frame.value(field));
        json.endObject();
        json.endObject();
    }

    public static void writeRecord(Writer output, DatasetMetadata metadata, TelemetryRecord record) throws IOException {
        JsonWriter json = new JsonWriter(output);
        json.setSerializeNulls(true);
        json.beginObject();
        json.name("schemaVersion").value(CombatFrame.SCHEMA_VERSION);
        json.name("sessionId").value(metadata.sessionId().toString());
        json.name("offsetNanos").value(record.nanoTime() - metadata.startNanos());
        if (record instanceof CombatFrame frame) {
            json.name("type").value("frame");
            json.name("tick").value(frame.tick());
            json.name("values").beginObject();
            for (FrameField field : FIELDS) number(json, field.name(), frame.value(field));
            json.endObject();
        } else if (record instanceof CombatEvent event) {
            json.name("type").value(event.type());
            json.name("precedingTick").value(event.precedingTick());
            json.name("entityId").value(event.entityId());
            json.name("cancelledAtObservation").value(event.cancelled());
            number(json, "yaw", event.yaw());
            number(json, "pitch", event.pitch());
        } else if (record instanceof ReachObservation reach) {
            json.name("type").value("reachObservation");
            json.name("entityId").value(reach.entityId());
            number(json, "distance", reach.distance());
            number(json, "hitboxIntersection", reach.hitboxIntersection() < 0 ? Double.NaN : reach.hitboxIntersection());
            number(json, "lineOfSight", reach.lineOfSight() < 0 ? Double.NaN : reach.lineOfSight());
            json.name("expandedCompensatedBox").beginArray();
            for (double value : new double[]{reach.minX(), reach.minY(), reach.minZ(), reach.maxX(), reach.maxY(), reach.maxZ()}) {
                if (Double.isFinite(value)) json.value(value); else json.nullValue();
            }
            json.endArray();
        } else {
            throw new IllegalArgumentException("Unknown telemetry record");
        }
        json.endObject();
        output.write('\n');
    }

    public static void writeMetadata(Writer output, DatasetSession session, boolean complete, long now) throws IOException {
        DatasetMetadata m = session.metadata;
        JsonWriter json = new JsonWriter(output);
        json.setSerializeNulls(true);
        json.setIndent("  ");
        json.beginObject();
        json.name("schemaVersion").value(CombatFrame.SCHEMA_VERSION);
        json.name("datasetVersion").value(DATASET_VERSION);
        json.name("sessionId").value(m.sessionId().toString());
        json.name("playerId").value(m.playerId());
        json.name("startTimestamp").value(m.startTimestamp());
        json.name("label").value(m.label().name());
        json.name("labelSource").value(m.labelSource().name());
        json.name("cheatFamily").value(m.cheatFamily());
        json.name("clientFamily").value(m.clientFamily());
        json.name("configuration").value(m.configuration());
        // Collection metadata: grouping and planning only, never a model input.
        json.name("scenario").value(m.scenario());
        json.name("assistStrength").value(m.assistStrength().name());
        json.name("notes").value(m.notes());
        json.name("minecraftProtocol").value(m.minecraftProtocol());
        json.name("pluginVersion").value(m.pluginVersion());
        json.name("continuousSize").value(m.continuousSize());
        json.name("attackBefore").value(m.attackBefore());
        json.name("attackAfter").value(m.attackAfter());
        json.name("durationMs").value(Math.max(0, now - m.startNanos()) / 1_000_000L);
        json.name("frames").value(session.framesWritten());
        json.name("records").value(session.recordsWritten());
        json.name("droppedRecords").value(session.dropped());
        json.name("complete").value(complete);
        json.name("usableWithoutReview").value(complete && session.dropped() == 0 && session.failure() == null);
        json.name("closeReason").value(session.closeReason());
        json.name("failure").value(session.failure());
        json.endObject();
        json.flush();
        output.write('\n');
    }

    static void number(JsonWriter json, String key, double value) throws IOException {
        json.name(key);
        if (Double.isFinite(value)) json.value(value); else json.nullValue();
    }
}
