package dev.aeroac.neural.training;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aeroac.neural.dataset.DatasetJson;
import dev.aeroac.neural.dataset.SnapshotReviews;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads a dataset root (metadata/, raw/, snapshots/, reviews/) and audits every session with the
 * rules of ml/aeroml/audit/sessions.py: UNUSABLE sessions are never read into training, REVIEW
 * sessions only on request, GOOD sessions always.
 */
public final class TrainingDataset {
    public enum Verdict { GOOD, REVIEW, UNUSABLE }

    /** One audited recording; {@code session} is null when it could not be read at all. */
    public record Audit(String sessionId, String label, Verdict verdict, List<String> reasons, TrainingSession session) { }

    private static final Set<String> EVENT_TYPES = Set.of("attack", "swing", "reachObservation", "teleport",
            "respawnOrWorldChange", "movementGap", "cancelledMovement", "invalidMovement", "CONFIG_RELOAD");
    private static final java.util.Map<String, String> LABEL_SOURCES = java.util.Map.of(
            "LEGIT", "LAB_LEGIT", "CHEAT", "LAB_CHEAT", "UNLABELED", "PRODUCTION_UNLABELED");
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,128}");
    // AuditPolicy defaults.
    private static final int MIN_FRAMES = 200;
    private static final int MIN_ATTACKS = 5;
    private static final double MIN_DURATION_SECONDS = 20;
    private static final double MIN_TARGET_PRESENT_RATE = 0.25;
    private static final double MIN_AIM_ERROR_KNOWN_RATE = 0.20;
    private static final double MAX_SAMPLING_GAP_MS = 150;
    private static final double MAX_SAMPLING_GAP_RATE = 0.02;
    private static final int MIN_SEGMENT_FRAMES = 32;
    /** What to do when there is nothing to train on yet. */
    static final String NO_RECORDINGS = "Записей боёв ещё нет. Сначала запишите бои: включите запись в центре обучения "
            + "(/aero training), затем /aero rec <ник> для честной игры и /aero rec <ник> aimassist с включённым читом, "
            + "дерись 1-2 минуты (можно с ботом: /aero bot), остановка /aero rec stop <ник>. "
            + "Нужно хотя бы 5 записей без чита и 5 с читом.";

    /** Staff CHEAT verdicts count as aim assistance, as in ml/aeroml/dataset/reviews.py. */
    public static final String STAFF_CHEAT_FAMILY = "aim-assist-staff-verdict";

    private TrainingDataset() { }

    /** Audits every session under {@code root}. Sessions are sorted by file name, like the Python loader. */
    public static List<Audit> audit(Path root) throws IOException {
        Path metadata = root.resolve("metadata");
        if (!Files.isDirectory(metadata)) throw new TrainingException(NO_RECORDINGS);
        TreeMap<String, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(metadata, "session-*.json")) {
            for (Path path : stream) files.put(path.getFileName().toString(), path);
        }
        if (files.isEmpty()) throw new TrainingException(NO_RECORDINGS);
        List<Audit> audits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path path : files.values()) {
            Audit audit;
            try {
                audit = audit(read(path, root.resolve("raw")));
            } catch (IOException | RuntimeException error) {
                String id = path.getFileName().toString().replaceFirst("^session-", "").replaceFirst("\\.json$", "");
                audits.add(new Audit(id, "UNKNOWN", Verdict.UNUSABLE, List.of("не читается: " + error.getMessage()), null));
                continue;
            }
            if (!seen.add(audit.sessionId())) {
                List<String> reasons = new ArrayList<>(audit.reasons());
                reasons.add("повторяющийся sessionId");
                audit = new Audit(audit.sessionId(), audit.label(), Verdict.UNUSABLE, reasons, null);
            }
            audits.add(audit);
        }
        return audits;
    }

    private record Loaded(TrainingSession session, int declaredFrames, long droppedRecords, boolean complete,
                          String failure, boolean truncatedLastLine, int malformedLines, int unknownTypes) { }

    private static Loaded read(Path metadataPath, Path rawDirectory) throws IOException {
        JsonObject meta = JsonParser.parseString(Files.readString(metadataPath, StandardCharsets.UTF_8)).getAsJsonObject();
        String sessionId = text(meta, "sessionId");
        String playerId = text(meta, "playerId");
        if (sessionId == null || !ID.matcher(sessionId).matches()) throw new IOException("неверный sessionId");
        if (playerId == null || !ID.matcher(playerId).matches()) throw new IOException("неверный playerId");
        if (!"dataset-v1".equals(textOr(meta, "datasetVersion", "dataset-v1"))) throw new IOException("неизвестная версия датасета");
        int version = meta.get("schemaVersion").getAsInt();
        if (version < 1 || version > CombatFrame.SCHEMA_VERSION) throw new IOException("схема записи " + version + " не поддерживается");
        for (String name : List.of("startTimestamp", "minecraftProtocol", "continuousSize", "attackBefore", "attackAfter")) {
            if (!meta.has(name) || meta.get(name).isJsonNull() || meta.get(name).getAsLong() < 0) throw new IOException("нет поля " + name);
        }
        String label = text(meta, "label");
        String expectedSource = LABEL_SOURCES.get(label);
        if (expectedSource == null) throw new IOException("неизвестная метка " + label);
        if (!expectedSource.equals(text(meta, "labelSource"))) throw new IOException("метка " + label + " не совпадает с источником");
        Path raw = rawDirectory.resolve("session-" + sessionId + ".jsonl");
        List<CombatFrame> frames = new ArrayList<>();
        int malformed = 0, unknown = 0;
        boolean truncated = false;
        long lastOffset = -1;
        try (BufferedReader reader = Files.newBufferedReader(raw, StandardCharsets.UTF_8)) {
            String line = nextNonBlank(reader);
            while (line != null) {
                String following = nextNonBlank(reader);
                JsonObject record;
                try {
                    record = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException torn) {
                    // Only the final line can be torn by a crash; anything earlier is real corruption.
                    if (following == null) truncated = true; else malformed++;
                    line = following;
                    continue;
                }
                if (record.get("schemaVersion") == null || record.get("schemaVersion").getAsInt() != version
                        || !sessionId.equals(text(record, "sessionId"))) {
                    throw new IOException("запись с чужой схемой или sessionId");
                }
                long offset = record.get("offsetNanos").getAsLong();
                if (offset < 0 || offset < lastOffset) throw new IOException("нарушен порядок времени");
                lastOffset = offset;
                String type = text(record, "type");
                if ("frame".equals(type)) {
                    frames.add(new CombatFrame(record.get("tick").getAsLong(), offset,
                            DatasetJson.readValues(record.getAsJsonObject("values"), version)));
                } else if (type == null || (!EVENT_TYPES.contains(type) && !type.startsWith("flag:"))) {
                    unknown++;
                }
                line = following;
            }
        }
        for (int i = 1; i < frames.size(); i++) {
            if (frames.get(i).tick() <= frames.get(i - 1).tick()) throw new IOException("тики кадров не возрастают");
            if (frames.get(i).nanoTime() <= frames.get(i - 1).nanoTime()) throw new IOException("время кадров не возрастает");
        }
        TrainingSession session = new TrainingSession(sessionId, playerId, emptyToNull(text(meta, "clientFamily")),
                emptyToNull(text(meta, "configuration")), label, textOr(meta, "labelSource", ""),
                emptyToNull(text(meta, "cheatFamily")), emptyToNull(text(meta, "notes")),
                textOr(meta, "pluginVersion", "unknown"), longOr(meta, "durationMs", 0),
                frames.toArray(new CombatFrame[0]), false);
        String failure = text(meta, "failure");
        return new Loaded(session, (int) longOr(meta, "frames", 0), longOr(meta, "droppedRecords", 0),
                meta.has("complete") && meta.get("complete").getAsBoolean(), failure, truncated, malformed, unknown);
    }

    private static Audit audit(Loaded loaded) {
        TrainingSession session = loaded.session();
        CombatFrame[] frames = session.frames();
        List<String> unusable = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (frames.length == 0) unusable.add("нет кадров");
        if (loaded.malformedLines() > 0) unusable.add("повреждённые строки: " + loaded.malformedLines());
        if (frames.length != loaded.declaredFrames()) unusable.add("число кадров не совпадает с метаданными");
        if (!loaded.complete()) reasons.add("запись не завершена");
        if (loaded.failure() != null) reasons.add("ошибка записи: " + loaded.failure());
        if (loaded.droppedRecords() > 0) reasons.add("потеряно записей: " + loaded.droppedRecords());
        if (loaded.truncatedLastLine()) reasons.add("обрезана последняя строка");
        int tickGaps = 0;
        for (int i = 1; i < frames.length; i++) if (frames[i].tick() - frames[i - 1].tick() != 1) tickGaps++;
        if (tickGaps > 0) reasons.add("дыры в тиках: " + tickGaps);
        if (loaded.unknownTypes() > 0) reasons.add("неизвестные записи: " + loaded.unknownTypes());
        double duration = session.durationMs() / 1000.0;
        if (frames.length > 0 && frames.length < MIN_FRAMES) reasons.add("слишком коротко: " + frames.length + " кадров");
        if (duration < MIN_DURATION_SECONDS) reasons.add(String.format(java.util.Locale.ROOT, "слишком коротко: %.1f с", duration));
        if (frames.length > 0) {
            int attacks = 0, present = 0, aimKnown = 0, finiteIntervals = 0, gaps = 0;
            boolean rotation = true;
            for (CombatFrame frame : frames) {
                if (!Double.isFinite(frame.value(FrameField.YAW)) || !Double.isFinite(frame.value(FrameField.PITCH))) rotation = false;
                if (frame.value(FrameField.ATTACK) == 1) attacks++;
                if (frame.value(FrameField.TARGET_PRESENT) == 1) present++;
                if (Double.isFinite(frame.value(FrameField.AIM_ERROR_TOTAL))) aimKnown++;
                double interval = frame.value(FrameField.SAMPLE_INTERVAL_MS);
                if (Double.isFinite(interval)) {
                    finiteIntervals++;
                    if (interval > MAX_SAMPLING_GAP_MS) gaps++;
                }
            }
            if (!rotation) unusable.add("нет данных о поворотах");
            int longest = 0;
            for (int[] segment : session.segments()) longest = Math.max(longest, segment[1] - segment[0]);
            if (attacks == 0) reasons.add("нет боя: ни одного удара");
            else if (attacks < MIN_ATTACKS) reasons.add("мало ударов: " + attacks);
            if ((double) present / frames.length < MIN_TARGET_PRESENT_RATE) reasons.add("редко есть цель");
            if ((double) aimKnown / frames.length < MIN_AIM_ERROR_KNOWN_RATE) reasons.add("редко известна ошибка прицела");
            if (finiteIntervals > 0 && (double) gaps / finiteIntervals > MAX_SAMPLING_GAP_RATE) reasons.add("большие пропуски между кадрами");
            if (longest < MIN_SEGMENT_FRAMES) reasons.add("нет непрерывного участка: " + longest + " кадров");
        }
        if (session.cheat() && session.cheatFamily() == null) unusable.add("CHEAT без типа чита");
        if (session.cheat() && (session.clientFamily() == null || session.configuration() == null)) {
            reasons.add("у CHEAT не указан клиент или конфигурация");
        }
        if ("UNLABELED".equals(session.label())) reasons.add("без метки");
        Verdict verdict = !unusable.isEmpty() ? Verdict.UNUSABLE : !reasons.isEmpty() ? Verdict.REVIEW : Verdict.GOOD;
        List<String> all = new ArrayList<>(unusable);
        all.addAll(reasons);
        return new Audit(session.sessionId(), session.label(), verdict, List.copyOf(all), verdict == Verdict.UNUSABLE ? null : session);
    }

    /**
     * Snapshots a moderator marked CHEAT or LEGIT in the GUI, each as a one-segment session labelled
     * STAFF_REVIEWED. Unreadable or mismatched files are skipped, as in ml/aeroml/dataset/reviews.py.
     */
    public static List<TrainingSession> staffReviews(Path root) {
        Path snapshots = root.resolve("snapshots");
        Path reviews = SnapshotReviews.directoryFor(snapshots);
        List<TrainingSession> sessions = new ArrayList<>();
        if (!Files.isDirectory(reviews)) return sessions;
        List<Path> files;
        try (Stream<Path> stream = Files.list(reviews)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException error) {
            return sessions;
        }
        for (Path path : files) {
            try {
                TrainingSession session = staffSession(path, snapshots);
                if (session != null) sessions.add(session);
            } catch (IOException | RuntimeException skipped) {
                // A bad review is not a reason to fail a training run.
            }
        }
        return sessions;
    }

    private static TrainingSession staffSession(Path reviewPath, Path snapshots) throws IOException {
        JsonObject review = JsonParser.parseString(Files.readString(reviewPath, StandardCharsets.UTF_8)).getAsJsonObject();
        String verdict = text(review, "verdict");
        String name = textOr(review, "snapshotFile", "");
        if (!"CHEAT".equals(verdict) && !"LEGIT".equals(verdict)) return null;
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        JsonObject snapshot = JsonParser.parseString(Files.readString(snapshots.resolve(name), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"evidenceSnapshot".equals(text(snapshot, "kind")) || !java.util.Objects.equals(text(snapshot, "eventId"), text(review, "eventId"))) return null;
        int version = snapshot.get("schemaVersion").getAsInt();
        if (version < 1 || version > CombatFrame.SCHEMA_VERSION || !snapshot.has("frames")) return null;
        List<JsonObject> raw = new ArrayList<>();
        for (JsonElement element : snapshot.getAsJsonArray("frames")) raw.add(element.getAsJsonObject());
        if (raw.isEmpty()) return null;
        long minimum = Long.MAX_VALUE;
        for (JsonObject frame : raw) minimum = Math.min(minimum, frame.get("offsetNanos").getAsLong());
        CombatFrame[] frames = new CombatFrame[raw.size()];
        for (int i = 0; i < frames.length; i++) {
            JsonObject frame = raw.get(i);
            frames[i] = new CombatFrame(frame.get("tick").getAsLong(), frame.get("offsetNanos").getAsLong() - minimum,
                    DatasetJson.readValues(frame.getAsJsonObject("values"), version));
            if (i > 0 && (frames[i].tick() <= frames[i - 1].tick() || frames[i].nanoTime() <= frames[i - 1].nanoTime())) return null;
        }
        boolean cheat = "CHEAT".equals(verdict);
        return new TrainingSession("review-" + text(snapshot, "eventId"), text(snapshot, "playerId"), null, null, verdict,
                "STAFF_REVIEWED", cheat ? STAFF_CHEAT_FAMILY : null,
                "staff review by " + textOr(review, "reviewer", "?"), "evidence-snapshot",
                frames[frames.length - 1].nanoTime() / 1_000_000L, frames, true);
    }

    private static String nextNonBlank(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) if (!line.isBlank()) return line;
        return null;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String textOr(JsonObject object, String key, String fallback) {
        String value = text(object, key);
        return value == null ? fallback : value;
    }

    private static long longOr(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
