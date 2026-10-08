package dev.aeroac.neural.admin.training;

import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.training.JavaTrainer;
import dev.aeroac.neural.training.ModelLibrary;
import dev.aeroac.neural.training.TrainingException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Trains inside the plugin with {@link JavaTrainer}: no Python, no service, no token. The run uses
 * its own low-priority threads and never the server tick; the screens read the cached job state.
 *
 * <p>A finished run is a candidate in models/trained/&lt;name&gt;, never the running model: switching
 * to it is a separate, deliberate {@code /aero models use <name>}.
 */
public final class LocalTrainingServiceClient implements TrainingServiceClient {
    /** Operator settings from neural.training.local.*. */
    public record Options(int epochs, int threads, boolean includeStaffReviews, boolean includeReview, String split) {
        public Options(int epochs, int threads, boolean includeStaffReviews, boolean includeReview) {
            this(epochs, threads, includeStaffReviews, includeReview, "auto");
        }
    }

    private final Path datasetRoot;
    private final Path modelsRoot;
    private final Options options;
    private final ExecutorService worker;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile AtomicBoolean cancel = new AtomicBoolean();
    private volatile TrainingJob job;
    private volatile EvaluationSummary candidate = EvaluationSummary.NONE;
    private volatile boolean closed;

    public LocalTrainingServiceClient(Path datasetRoot, Path modelsRoot, Options options) {
        this.datasetRoot = datasetRoot;
        this.modelsRoot = modelsRoot;
        this.options = options;
        this.job = idle("Обучение внутри плагина готово: /aero training start flash");
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Aero-trainer-main");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        List<ModelLibrary.ModelInfo> trained = ModelLibrary.list(modelsRoot);
        if (!trained.isEmpty()) candidate = summary(trained.get(0));
    }

    private static TrainingJob idle(String message) {
        return new TrainingJob(TrainingJob.Status.IDLE, null, null, null, FeatureEncoder.FEATURE_SCHEMA_VERSION, null, null,
                0, 0, Double.NaN, Double.NaN, Double.NaN, 0, System.currentTimeMillis(), message);
    }

    @Override public TrainingJob job() { return job; }
    /** True while a run is in progress; a reload keeps such a client instead of killing the run. */
    public boolean running() { return running.get(); }
    @Override public EvaluationSummary currentEvaluation() { return EvaluationSummary.NONE; }
    @Override public EvaluationSummary candidateEvaluation() { return candidate; }
    @Override public void poll() { }
    @Override public boolean configured() { return !closed; }
    @Override public String unavailableReason() { return closed ? "Обучение остановлено вместе с плагином." : null; }

    @Override public void start(TrainingRequest request, Consumer<String> reply) {
        if (closed) { reply.accept("Обучение недоступно: плагин выключается."); return; }
        if (!running.compareAndSet(false, true)) {
            reply.accept("Обучение уже идёт (" + job.message() + "). Отмена: /aero training cancel");
            return;
        }
        String name = ModelLibrary.newName(request.preset());
        Path output = ModelLibrary.trainedRoot(modelsRoot).resolve(name);
        JavaTrainer.Settings settings = ("pro".equals(request.preset()) ? JavaTrainer.Settings.pro() : JavaTrainer.Settings.flash())
                .withEpochs(options.epochs()).withThreads(options.threads()).withSeed(request.seed())
                .withData(false, options.includeReview(), options.includeStaffReviews()).withSplit(options.split());
        AtomicBoolean flag = new AtomicBoolean();
        cancel = flag;
        long started = System.currentTimeMillis();
        job = state(TrainingJob.Status.QUEUED, name, request, 0, settings.epochs(), Double.NaN, Double.NaN, started, "В очереди");
        try {
            worker.execute(() -> run(name, output, request, settings, flag, started, reply));
        } catch (RejectedExecutionException stopped) {
            running.set(false);
            reply.accept("Обучение недоступно: плагин выключается.");
            return;
        }
        reply.accept("Обучение " + request.preset() + " запущено в фоне (потоков: " + settings.threads() + ", эпох максимум: "
                + settings.epochs() + "). Ход: /aero training status");
    }

    private void run(String name, Path output, TrainingRequest request, JavaTrainer.Settings settings, AtomicBoolean flag,
                     long started, Consumer<String> reply) {
        try {
            JavaTrainer.Result result = new JavaTrainer(settings, (stage, epoch, total, loss, validation, message) ->
                    job = state(status(stage), name, request, stage.equals("training") ? epoch + 1 : epoch, total, loss, validation, started, message),
                    flag).run(datasetRoot, output);
            ModelLibrary.ModelInfo info = ModelLibrary.read(result.bundle());
            if (info != null) candidate = summary(info);
            String quality = info == null ? "" : String.format(java.util.Locale.ROOT, " ROC-AUC на тесте %.3f.", info.testRocAuc());
            job = state(TrainingJob.Status.CANDIDATE, name, request, settings.epochs(), settings.epochs(), Double.NaN, Double.NaN,
                    started, "Готово: модель " + name + "." + quality + " Включить: /aero models use " + name);
            reply.accept("Обучение завершено: " + name + "." + quality);
            for (String warning : result.warnings()) reply.accept("  ! " + warning);
            reply.accept("Посмотреть: /aero models | включить: /aero models use " + name);
        } catch (TrainingException error) {
            boolean cancelled = flag.get();
            job = state(cancelled ? TrainingJob.Status.CANCELLED : TrainingJob.Status.FAILED, name, request, 0, settings.epochs(),
                    Double.NaN, Double.NaN, started, error.getMessage());
            reply.accept(cancelled ? "Обучение отменено." : "Обучение остановлено: " + error.getMessage()
                    + " Проверка записей: " + modelsRoot.resolve("last-audit.json"));
            discard(output);
        } catch (Throwable error) {
            String text = error instanceof java.io.IOException
                    ? "Не удалось прочитать записи или сохранить модель: " + error.getMessage()
                    : "Ошибка обучения: " + error;
            job = state(TrainingJob.Status.FAILED, name, request, 0, settings.epochs(), Double.NaN, Double.NaN, started, text);
            reply.accept(text);
            discard(output);
        } finally {
            running.set(false);
        }
    }

    @Override public void cancel(Consumer<String> reply) {
        if (!running.get()) { reply.accept("Сейчас ничего не обучается."); return; }
        cancel.set(true);
        reply.accept("Отмена запрошена; обучение остановится после текущего шага.");
    }

    private static TrainingJob.Status status(String stage) {
        return switch (stage) {
            case "auditing" -> TrainingJob.Status.AUDITING;
            case "preparing" -> TrainingJob.Status.PREPARING;
            case "training" -> TrainingJob.Status.TRAINING;
            case "calibrating" -> TrainingJob.Status.CALIBRATING;
            case "evaluating" -> TrainingJob.Status.EVALUATING;
            case "exporting" -> TrainingJob.Status.EXPORTING;
            case "completed" -> TrainingJob.Status.COMPLETED;
            default -> TrainingJob.Status.TRAINING;
        };
    }

    private static TrainingJob state(TrainingJob.Status status, String name, TrainingRequest request, int epoch, int total,
                                     double loss, double validation, long started, String message) {
        double progress = total <= 0 ? Double.NaN : Math.min(1.0, (double) epoch / total);
        return new TrainingJob(status, name, request.preset(), "dataset-v1", request.featureSchemaVersion(), request.window(),
                String.join(",", request.heads()), epoch, total, progress, loss, validation,
                (System.currentTimeMillis() - started) / 1000, System.currentTimeMillis(), message);
    }

    /** The screens' view of a trained bundle. */
    public static EvaluationSummary summary(ModelLibrary.ModelInfo info) {
        List<String> caveats = new ArrayList<>(info.warnings());
        if (info.synthetic()) caveats.add(0, "Синтетические данные: только проверка, не для сервера.");
        long produced;
        try {
            produced = info.created() == null ? System.currentTimeMillis() : java.time.OffsetDateTime.parse(info.created()).toInstant().toEpochMilli();
        } catch (RuntimeException unparsable) {
            produced = System.currentTimeMillis();
        }
        return new EvaluationSummary(info.modelVersion(), "dataset-v1", FeatureEncoder.FEATURE_SCHEMA_VERSION,
                info.testRocAuc(), info.testPrAuc(), 0.001, info.testTprAtFpr(), Double.NaN, Double.NaN,
                info.calibrated() ? EvaluationSummary.Calibration.CALIBRATED : EvaluationSummary.Calibration.UNCALIBRATED,
                info.unknownClientTest() ? EvaluationSummary.Population.UNKNOWN_CLIENT : EvaluationSummary.Population.KNOWN_CLIENT,
                Map.of(), Map.of(), Map.of(), caveats, produced);
    }

    /** Keeps the audit of a failed run at models/last-audit.json, then removes the partial folder. */
    private void discard(Path directory) {
        try {
            Path audit = directory.resolve("dataset_audit.json");
            if (java.nio.file.Files.exists(audit)) {
                java.nio.file.Files.copy(audit, modelsRoot.resolve("last-audit.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // Diagnostics only.
        }
        deleteQuietly(directory);
    }

    private static void deleteQuietly(Path directory) {
        try (var stream = java.nio.file.Files.walk(directory)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
        } catch (java.io.IOException | RuntimeException ignored) {
            // A leftover partial folder has no manifest and is never listed as a model.
        }
    }

    @Override public void close() {
        closed = true;
        cancel.set(true);
        worker.shutdownNow();
    }
}
