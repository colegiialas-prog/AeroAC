package ac.grim.grimac.neural.admin.training;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads session metadata off the main thread and hands the interface a cached summary.
 *
 * <p>Opening a screen never reads a file. A caller gets whatever the last background pass produced
 * — possibly {@link DatasetSummary#PENDING} on the very first open — and a refresh is scheduled if
 * the cache has aged out. That is the whole design: a dataset grows without bound, an inventory
 * opens in one tick, and the two must never be on the same thread.
 *
 * <p>Only {@code metadata/} is read. The raw telemetry is left alone, and the audit verdicts and
 * human review entries are imported from the files the Python tooling writes rather than recomputed
 * here, because the runtime cannot reproduce an audit and must not pretend otherwise.
 */
public final class DatasetSummaryService implements AutoCloseable {
    private static final int MAX_ATTENTION = 90;

    private final Path root;
    private final int cacheMillis;
    private final int maxSessions;
    private final int recentCount;
    private final ExecutorService worker;
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile DatasetSummary cached = DatasetSummary.PENDING;
    private volatile boolean closed;

    public DatasetSummaryService(Path root, int cacheSeconds, int maxSessions, int recentCount) {
        this.root = root;
        this.cacheMillis = Math.max(1, cacheSeconds) * 1000;
        this.maxSessions = Math.max(1, maxSessions);
        this.recentCount = Math.max(1, recentCount);
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Aero-admin-dataset-summary");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Never blocks. Returns the cached summary and starts a refresh when it has gone stale. */
    public DatasetSummary snapshot() {
        DatasetSummary current = cached;
        if (!closed && System.currentTimeMillis() - current.builtAtMillis() > cacheMillis) refresh();
        return current;
    }

    /** Forces the next snapshot to be rebuilt, for a reload or after a session closes. */
    public void invalidate() {
        cached = DatasetSummary.PENDING;
        refresh();
    }

    private void refresh() {
        if (closed || !loading.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    DatasetSummary built = build();
                    if (!closed) cached = built;
                } catch (Exception error) {
                    cached = failure(error);
                } finally {
                    loading.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            loading.set(false);
        }
    }

    private DatasetSummary failure(Exception error) {
        return new DatasetSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), false, System.currentTimeMillis(),
                error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private DatasetSummary build() throws Exception {
        Path metadata = root.resolve("metadata");
        if (!Files.isDirectory(metadata)) {
            return new DatasetSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    List.of(), List.of(), false, System.currentTimeMillis(), null);
        }
        Map<String, JsonObject> imported = importedRows();
        Map<String, JsonObject> matched = new HashMap<>();

        record FileEntry(Path path, long modified) { }
        var latest = new java.util.PriorityQueue<FileEntry>(Comparator.comparingLong(FileEntry::modified)
                .thenComparing(entry -> entry.path().toString()));
        long found = 0;
        try (var stream = Files.newDirectoryStream(metadata, "session-*.json")) {
            for (Path file : stream) {
                if (closed) throw new java.io.IOException("summary reader closed");
                if (!Files.isRegularFile(file)) continue;
                found++;
                latest.add(new FileEntry(file, Files.getLastModifiedTime(file).toMillis()));
                if (latest.size() > maxSessions) latest.poll();
            }
        }
        List<SessionRecord> records = new ArrayList<>(latest.size());
        for (FileEntry file : latest) records.add(read(file.path(), imported, matched));
        DatasetSummary result = summarise(records, found > maxSessions);
        if (!records.isEmpty() && matched.size() == records.size()) {
            long windows = 0;
            double combat = 0;
            int highPing = 0;
            Map<String, Integer> ping = new HashMap<>();
            try {
                for (SessionRecord record : records) {
                    JsonObject row = matched.get(record.sessionId());
                    long count = JsonFiles.integer(row, "attackWindows", -1);
                    JsonObject metrics = row.getAsJsonObject("metrics");
                    double seconds = JsonFiles.number(metrics, "combatSeconds");
                    if (count < 0 || !Double.isFinite(seconds) || seconds < 0) return result;
                    windows += count;
                    combat += seconds;
                    if (record.legit()) {
                        double ms = JsonFiles.number(metrics, "medianPingMs");
                        String bucket = !Double.isFinite(ms) || ms < 0 ? "unknown" : ms < 50 ? "0-49ms"
                                : ms < 100 ? "50-99ms" : ms < 150 ? "100-149ms" : ms < 200 ? "150-199ms" : "200+ms";
                        count(ping, bucket);
                        if (Double.isFinite(ms) && ms >= 150) highPing++;
                    }
                }
                result = result.withAudit(new AuditTotals(windows, combat, ping, highPing));
            } catch (RuntimeException malformed) { /* all totals remain explicitly unmeasured */ }
        }
        return result;
    }

    private DatasetSummary summarise(List<SessionRecord> records, boolean truncated) {
        Set<String> players = new HashSet<>();
        Map<String, Integer> cheatFamilies = new HashMap<>();
        Map<String, Integer> clientFamilies = new HashMap<>();
        Map<String, Integer> configurations = new HashMap<>();
        Map<String, Integer> assistStrengths = new HashMap<>();
        Map<String, Integer> cheatScenarios = new HashMap<>();
        Map<String, Integer> legitScenarios = new HashMap<>();
        Map<String, Integer> legitProtocols = new HashMap<>();
        Map<String, Integer> auditVerdicts = new HashMap<>();
        int legit = 0, cheat = 0, unlabeled = 0, incomplete = 0, failed = 0, dropped = 0, audited = 0, reviewed = 0;
        long durationMs = 0, frames = 0, recordCount = 0;

        for (SessionRecord record : records) {
            if (record.playerId() != null) players.add(record.playerId());
            durationMs += record.durationMs();
            frames += record.frames();
            recordCount += record.records();
            if (!record.complete()) incomplete++;
            if (record.failure() != null) failed++;
            if (record.droppedRecords() > 0) dropped++;
            if (record.audited()) {
                audited++;
                count(auditVerdicts, record.auditVerdict());
            }
            if (record.humanReviewed()) reviewed++;

            if (record.cheat()) {
                cheat++;
                count(cheatFamilies, record.cheatFamily());
                count(clientFamilies, record.clientFamily());
                count(configurations, (record.clientFamily() == null ? "(unset)" : record.clientFamily())
                        + "/" + (record.configuration() == null ? "(unset)" : record.configuration()));
                count(assistStrengths, record.assistStrength());
                count(cheatScenarios, record.scenario());
            } else if (record.legit()) {
                legit++;
                count(legitScenarios, record.scenario());
                count(legitProtocols, record.minecraftProtocol() <= 0 ? null
                        : String.valueOf(record.minecraftProtocol()));
            } else {
                unlabeled++;
            }
        }

        List<SessionRecord> byNewest = new ArrayList<>(records);
        byNewest.sort(Comparator.comparingLong(SessionRecord::startTimestamp).reversed());
        List<SessionRecord> recent = byNewest.subList(0, Math.min(recentCount, byNewest.size()));
        List<SessionRecord> attention = new ArrayList<>();
        for (SessionRecord record : byNewest) {
            if (record.needsAttention() && attention.size() < MAX_ATTENTION) attention.add(record);
        }

        return new DatasetSummary(records.size(), players.size(), legit, cheat, unlabeled,
                incomplete, failed, dropped, audited, reviewed, durationMs, frames, recordCount,
                Map.copyOf(cheatFamilies), Map.copyOf(clientFamilies), Map.copyOf(configurations),
                Map.copyOf(assistStrengths), Map.copyOf(cheatScenarios), Map.copyOf(legitScenarios),
                Map.copyOf(legitProtocols), Map.copyOf(auditVerdicts),
                List.copyOf(recent), List.copyOf(attention), truncated, System.currentTimeMillis(), null);
    }

    private static void count(Map<String, Integer> target, String key) {
        String cleaned = key == null || key.isBlank() ? "(unset)" : key.trim();
        target.merge(cleaned, 1, Integer::sum);
    }

    private SessionRecord read(Path file, Map<String, JsonObject> imported, Map<String, JsonObject> matched) {
        String fallbackId = file.getFileName().toString().replaceFirst("^session-", "").replaceFirst("\\.json$", "");
        try {
            byte[] bytes = JsonFiles.bytes(file, 65536);
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = JsonFiles.text(json, "sessionId");
            String label = JsonFiles.text(json, "label");
            if (id == null || !id.equals(fallbackId) || !Set.of("LEGIT", "CHEAT", "UNLABELED").contains(label)) {
                throw new IllegalArgumentException("invalid session identity or label");
            }
            JsonObject row = imported.get(id);
            if (row != null && matches(row, bytes, id, label)) matched.put(id, row);
            else row = null;
            String verdict = row == null ? null : JsonFiles.text(row, "verdict");
            String reason = null;
            if (row != null && row.has("reasons") && row.get("reasons").isJsonArray() && row.getAsJsonArray("reasons").size() > 0) {
                reason = row.getAsJsonArray("reasons").get(0).getAsString();
            }
            JsonObject review = row != null && row.has("humanReview") ? row.getAsJsonObject("humanReview") : null;
            if (review != null && (!label.equals(JsonFiles.text(review, "expectedLabel"))
                    || JsonFiles.text(review, "reviewer") == null || JsonFiles.text(review, "reviewNotes") == null
                    || JsonFiles.text(review, "reviewedAt") == null || JsonFiles.text(review, "sha256") == null
                    || !JsonFiles.text(review, "sha256").matches("[a-f0-9]{64}"))) review = null;
            if (review != null) java.time.OffsetDateTime.parse(JsonFiles.text(review, "reviewedAt"));
            long frames = nonnegative(json, "frames"), records = nonnegative(json, "records");
            return new SessionRecord(id, JsonFiles.text(json, "playerId"), nonnegative(json, "startTimestamp"),
                    label, JsonFiles.text(json, "labelSource"), JsonFiles.text(json, "cheatFamily"),
                    JsonFiles.text(json, "clientFamily"), JsonFiles.text(json, "configuration"), JsonFiles.text(json, "scenario"),
                    JsonFiles.text(json, "assistStrength"), Math.toIntExact(nonnegative(json, "minecraftProtocol")),
                    nonnegative(json, "durationMs"), frames, records, nonnegative(json, "droppedRecords"),
                    JsonFiles.bool(json, "complete"), JsonFiles.text(json, "closeReason"), JsonFiles.text(json, "failure"),
                    verdict, reason, review == null ? null : JsonFiles.text(review, "reviewer"),
                    review == null ? null : JsonFiles.text(review, "reviewedAt"),
                    review == null ? null : JsonFiles.text(review, "expectedLabel"));
        } catch (Exception unreadable) {
            matched.remove(fallbackId);
            return new SessionRecord(fallbackId, null, 0, "UNKNOWN", null, null, null, null, null, null,
                    0, 0, 0, 0, 0, false, null, "unreadable metadata: " + unreadable.getClass().getSimpleName(),
                    null, null, null, null, null);
        }
    }

    private boolean matches(JsonObject row, byte[] bytes, String id, String label) {
        try {
            if (!label.equals(JsonFiles.text(row, "label"))) return false;
            if (!Set.of("GOOD", "REVIEW", "UNUSABLE").contains(JsonFiles.text(row, "verdict"))) return false;
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!digest.equals(JsonFiles.text(row, "metadataSha256"))) return false;
            Path raw = root.resolve("raw").resolve("session-" + id + ".jsonl");
            return Files.size(raw) == JsonFiles.integer(row, "rawBytes", -1)
                    && Long.toString(Files.getLastModifiedTime(raw).to(TimeUnit.NANOSECONDS)).equals(JsonFiles.text(row, "rawModifiedNs"));
        } catch (Exception changedOrMissing) { return false; }
    }

    private Map<String, JsonObject> importedRows() {
        Map<String, JsonObject> result = new HashMap<>();
        Path report = root.resolve("audit").resolve("latest.json");
        try {
            JsonObject json = JsonFiles.read(report, 32 * 1024 * 1024);
            if (JsonFiles.integer(json, "adminImportVersion", 0) != 1) return result;
            Set<String> duplicates = new HashSet<>();
            for (JsonElement element : json.getAsJsonArray("sessions")) {
                JsonObject row = element.getAsJsonObject();
                String id = JsonFiles.text(row, "sessionId");
                if (id == null) continue;
                if (result.putIfAbsent(id, row) != null) duplicates.add(id);
            }
            duplicates.forEach(result::remove);
        } catch (Exception unavailable) { result.clear(); }
        return result;
    }

    private static long nonnegative(JsonObject json, String key) {
        long value = JsonFiles.integer(json, key, 0);
        if (value < 0) throw new IllegalArgumentException(key + " must be nonnegative");
        return value;
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
