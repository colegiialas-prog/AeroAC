package dev.aeroac.neural.admin.training;

import dev.aeroac.locale.AeroMessages;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only adapter for reports exported outside Minecraft. No process launch, job submission or deployment. */
public final class ReportDirectoryClient implements TrainingServiceClient {
    private record Cached(TrainingJob job, EvaluationSummary current, EvaluationSummary candidate, String problem) { }
    private final Path directory;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile boolean closed;
    private volatile Cached cached = new Cached(TrainingJob.disconnected(AeroMessages.tr("gui.reports.waiting")),
            EvaluationSummary.NONE, EvaluationSummary.NONE, AeroMessages.tr("gui.reports.waiting"));

    public ReportDirectoryClient(Path directory) {
        this.directory = directory;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Aero-training-report-import");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::poll, 0, 5, TimeUnit.SECONDS);
    }

    @Override public TrainingJob job() { return cached.job(); }
    @Override public EvaluationSummary currentEvaluation() { return cached.current(); }
    @Override public EvaluationSummary candidateEvaluation() { return cached.candidate(); }
    @Override public boolean configured() { return true; }
    @Override public String unavailableReason() { return cached.problem(); }

    @Override public void poll() {
        if (closed || !pending.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                Cached replacement;
                try {
                    TrainingJob job = readJob(directory.resolve("job.json"));
                    replacement = new Cached(job, readEvaluation(directory.resolve("current.json")),
                            readEvaluation(directory.resolve("candidate.json")),
                            job.status() == TrainingJob.Status.DISCONNECTED ? job.message() : null);
                } catch (Exception error) {
                    String message = AeroMessages.tr("gui.reports.failed") + error.getClass().getSimpleName()
                            + ": " + error.getMessage();
                    replacement = new Cached(TrainingJob.disconnected(message), EvaluationSummary.NONE, EvaluationSummary.NONE, message);
                } finally { pending.set(false); }
                if (!closed) cached = replacement;
            });
        } catch (RuntimeException stopped) { pending.set(false); }
    }

    private static TrainingJob readJob(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return TrainingJob.disconnected(AeroMessages.tr("gui.reports.no_snapshot"));
        JsonObject data = JsonFiles.read(path, 65536);
        if (JsonFiles.integer(data, "reportVersion", 0) != 1) throw new IllegalArgumentException(AeroMessages.tr("admin.training.unsupported_job_reportversion"));
        TrainingJob.Status status = TrainingJob.Status.valueOf(JsonFiles.text(data, "status"));
        long updated = JsonFiles.integer(data, "updatedAtMillis", 0);
        long now = System.currentTimeMillis();
        if (updated <= 0 || updated > now + 5000) throw new IllegalArgumentException(AeroMessages.tr("admin.training.invalid_job_timestamp"));
        int epoch = Math.toIntExact(JsonFiles.integer(data, "epoch", 0));
        int total = Math.toIntExact(JsonFiles.integer(data, "totalEpochs", 0));
        if (epoch < 0 || total < epoch) throw new IllegalArgumentException(AeroMessages.tr("admin.training.invalid_epochs"));
        TrainingJob job = new TrainingJob(status, JsonFiles.text(data, "jobId"), JsonFiles.text(data, "modelType"),
                JsonFiles.text(data, "datasetVersion"), Math.toIntExact(JsonFiles.integer(data, "featureSchemaVersion", 0)),
                JsonFiles.text(data, "window"), JsonFiles.text(data, "heads"), epoch, total,
                metric(data, "progress", 1), metric(data, "trainLoss", Double.POSITIVE_INFINITY),
                metric(data, "validationLoss", Double.POSITIVE_INFINITY), JsonFiles.integer(data, "elapsedSeconds", 0),
                updated, JsonFiles.text(data, "message"));
        return job.running() && now - updated > 60000 ? TrainingJob.disconnected(AeroMessages.tr("gui.reports.stale_snapshot")) : job;
    }

    static EvaluationSummary readEvaluation(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return EvaluationSummary.NONE;
        JsonObject data = JsonFiles.read(path, 1024 * 1024);
        if (JsonFiles.integer(data, "reportVersion", 0) != 1) throw new IllegalArgumentException(AeroMessages.tr("admin.training.use_aeroml_tools_export_admin_evaluation_to_export_reports"));
        long produced = JsonFiles.integer(data, "producedAtMillis", 0);
        if (produced <= 0 || produced > System.currentTimeMillis() + 5000) throw new IllegalArgumentException(AeroMessages.tr("admin.training.invalid_evaluation_timestamp"));
        String cohort = JsonFiles.text(data, "cohortId");
        if (cohort == null || !cohort.matches("[a-f0-9]{64}")) throw new IllegalArgumentException(AeroMessages.tr("admin.training.missing_evaluation_cohort_identity"));
        List<String> caveats = new ArrayList<>();
        if (data.has("caveats")) for (var item : data.getAsJsonArray("caveats")) {
            if (caveats.size() >= 12) break;
            String value = item.getAsString();
            caveats.add(value.substring(0, Math.min(400, value.length())));
        }
        return new EvaluationSummary(JsonFiles.text(data, "modelVersion"), JsonFiles.text(data, "datasetVersion"),
                Math.toIntExact(JsonFiles.integer(data, "featureSchemaVersion", 0)), metric(data, "rocAuc", 1),
                metric(data, "prAuc", 1), metric(data, "configuredFpr", 1), metric(data, "tprAtFpr", 1),
                metric(data, "falsePositivesPerCombatHour", Double.POSITIVE_INFINITY),
                metric(data, "medianDetectionSeconds", Double.POSITIVE_INFINITY),
                EvaluationSummary.Calibration.valueOf(JsonFiles.text(data, "calibration")),
                EvaluationSummary.Population.valueOf(JsonFiles.text(data, "population")),
                breakdown(data, "detectionByAssist"), breakdown(data, "detectionByClient"),
                breakdown(data, "detectionByScenario"), caveats, produced, cohort, breakdown(data, "detectionByPopulation"));
    }

    private static double metric(JsonObject data, String key, double max) {
        double value = JsonFiles.number(data, key);
        if (value < 0 || value > max) throw new IllegalArgumentException(AeroMessages.tr("admin.training.invalid") + key);
        return value;
    }

    private static Map<String, Double> breakdown(JsonObject data, String key) {
        Map<String, Double> result = new HashMap<>();
        if (!data.has(key)) return result;
        JsonObject map = data.getAsJsonObject(key);
        if (map.size() > 200) throw new IllegalArgumentException(AeroMessages.tr("admin.training.too_many_groups_in") + key);
        for (var entry : map.entrySet()) result.put(entry.getKey(), metric(map, entry.getKey(), 1));
        return result;
    }

    @Override public void close() { closed = true; worker.shutdownNow(); }
}
