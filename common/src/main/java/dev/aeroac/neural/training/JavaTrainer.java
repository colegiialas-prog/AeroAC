package dev.aeroac.neural.training;

import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.inference.ModelFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The training run of ml/aeroml/training/train.py, in the JVM: dataset in, model bundle out.
 *
 * <p>The order of operations is the same and is the part that matters: audit, split by player,
 * fit normalisation on the training fold only, train with AdamW and a cosine schedule while
 * selecting the epoch on the validation fold's low-FPR partial AUC, calibrate on a fold training
 * never saw, then evaluate on a test fold neither training nor calibration saw.
 *
 * <p>Runs on the caller's thread plus {@code threads} worker threads; nothing here touches the game.
 */
public final class JavaTrainer {

    /** Mirrors TrainingConfig in ml/aeroml/training/config.py. */
    public record Settings(String preset, String window, int sequenceLength, int stride, int epochs, int batchSize,
                           double learningRate, double weightDecay, double dropout, long seed,
                           List<String> heads, List<String> holdoutClients, boolean allowSynthetic,
                           boolean includeReview, boolean includeStaffReviews, double groupBalance,
                           boolean augment, double mirrorProbability, double channelDropout,
                           double selectionMaxFpr, int earlyStoppingPatience, int threads, long maxCacheBytes,
                           String split) {

        public static Settings flash() {
            return new Settings("flash", "attack", 31, 4, 30, 128, 2.0e-3, 1.0e-4, 0.1, 0,
                    List.of("overall", "aimAssist"), List.of(), false, false, false, 0.5, true, 0.5, 0.05,
                    0.05, 6, Math.max(1, Runtime.getRuntime().availableProcessors() / 2), 0, "auto");
        }

        public static Settings pro() {
            Settings flash = flash();
            return flash.with("pro", "continuous", 96);
        }

        Settings with(String preset, String window, int length) {
            return new Settings(preset, window, length, stride, epochs, batchSize, learningRate, weightDecay, dropout, seed,
                    heads, holdoutClients, allowSynthetic, includeReview, includeStaffReviews, groupBalance, augment,
                    mirrorProbability, channelDropout, selectionMaxFpr, earlyStoppingPatience, threads, maxCacheBytes, split);
        }

        public Settings withEpochs(int value) {
            return new Settings(preset, window, sequenceLength, stride, value, batchSize, learningRate, weightDecay, dropout, seed,
                    heads, holdoutClients, allowSynthetic, includeReview, includeStaffReviews, groupBalance, augment,
                    mirrorProbability, channelDropout, selectionMaxFpr, earlyStoppingPatience, threads, maxCacheBytes, split);
        }

        public Settings withSeed(long value) {
            return new Settings(preset, window, sequenceLength, stride, epochs, batchSize, learningRate, weightDecay, dropout, value,
                    heads, holdoutClients, allowSynthetic, includeReview, includeStaffReviews, groupBalance, augment,
                    mirrorProbability, channelDropout, selectionMaxFpr, earlyStoppingPatience, threads, maxCacheBytes, split);
        }

        public Settings withThreads(int value) {
            return new Settings(preset, window, sequenceLength, stride, epochs, batchSize, learningRate, weightDecay, dropout, seed,
                    heads, holdoutClients, allowSynthetic, includeReview, includeStaffReviews, groupBalance, augment,
                    mirrorProbability, channelDropout, selectionMaxFpr, earlyStoppingPatience, Math.max(1, value), maxCacheBytes, split);
        }

        public Settings withData(boolean synthetic, boolean review, boolean staff) {
            return new Settings(preset, window, sequenceLength, stride, epochs, batchSize, learningRate, weightDecay, dropout, seed,
                    heads, holdoutClients, synthetic, review, staff, groupBalance, augment,
                    mirrorProbability, channelDropout, selectionMaxFpr, earlyStoppingPatience, threads, maxCacheBytes, split);
        }

        /** auto: by player, falling back to by session when there are too few players; player; session. */
        public Settings withSplit(String value) {
            String mode = value == null ? "auto" : value.trim().toLowerCase(java.util.Locale.ROOT);
            if (!mode.equals("auto") && !mode.equals("player") && !mode.equals("session")) mode = "auto";
            return new Settings(preset, window, sequenceLength, stride, epochs, batchSize, learningRate, weightDecay, dropout, seed,
                    heads, holdoutClients, allowSynthetic, includeReview, includeStaffReviews, groupBalance, augment,
                    mirrorProbability, channelDropout, selectionMaxFpr, earlyStoppingPatience, threads, maxCacheBytes, mode);
        }

        public Settings withAugment(boolean value) {
            return new Settings(preset, window, sequenceLength, stride, epochs, batchSize, learningRate, weightDecay, dropout, seed,
                    heads, holdoutClients, allowSynthetic, includeReview, includeStaffReviews, groupBalance, value,
                    mirrorProbability, channelDropout, selectionMaxFpr, earlyStoppingPatience, threads, maxCacheBytes, split);
        }

        Map<String, Object> toJson(Path dataset, Path output) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("trainer", "java");
            data.put("dataset", dataset.toString());
            data.put("output", output.toString());
            data.put("window", window);
            data.put("sequence_length", sequenceLength);
            data.put("stride", stride);
            data.put("heads", heads);
            data.put("preset", preset);
            data.put("epochs", epochs);
            data.put("batch_size", batchSize);
            data.put("learning_rate", learningRate);
            data.put("weight_decay", weightDecay);
            data.put("dropout", dropout);
            data.put("seed", seed);
            data.put("split", split);
            data.put("holdout_clients", holdoutClients);
            data.put("early_stopping_patience", earlyStoppingPatience);
            data.put("allow_synthetic", allowSynthetic);
            data.put("include_review", includeReview);
            data.put("include_staff_reviews", includeStaffReviews);
            data.put("threads", threads);
            data.put("calibration", "platt");
            data.put("group_balance", groupBalance);
            data.put("augment", augment);
            data.put("mirror_probability", mirrorProbability);
            data.put("channel_dropout", channelDropout);
            data.put("selection_max_fpr", selectionMaxFpr);
            return data;
        }
    }

    /** Stage and epoch feedback; called from the training thread. */
    public interface Progress {
        void update(String stage, int epoch, int totalEpochs, double trainLoss, double validationLoss, String message);
    }

    /** What a finished run produced. */
    public record Result(Path bundle, String modelVersion, Map<String, Object> evaluation, boolean calibrated,
                         boolean synthetic, List<String> warnings) { }

    private static final Map<String, List<String>> HEAD_FAMILIES = Map.of(
            "overall", List.of("*"),
            "aimAssist", List.of("aimassist", "aim-assist", "aimbot", "aim"),
            "killAura", List.of("killaura", "kill-aura", "aura"),
            "triggerBot", List.of("triggerbot", "trigger-bot", "trigger"));

    private final Settings settings;
    private final Progress progress;
    private final AtomicBoolean cancelled;
    private final List<String> warnings = new ArrayList<>();

    public JavaTrainer(Settings settings, Progress progress, AtomicBoolean cancelled) {
        this.settings = settings;
        this.progress = progress == null ? (stage, epoch, total, loss, validation, message) -> { } : progress;
        this.cancelled = cancelled == null ? new AtomicBoolean() : cancelled;
    }

    static boolean headPositive(String head, TrainingSession session) {
        if (!session.cheat()) return false;
        List<String> families = HEAD_FAMILIES.get(head);
        if (families.equals(List.of("*"))) return true;
        String family = session.cheatFamily() == null ? "" : session.cheatFamily().trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (String candidate : families) if (family.equals(candidate) || family.startsWith(candidate + "-")) return true;
        return false;
    }

    private void notify(String stage, int epoch, double loss, double validation, String message) {
        try {
            progress.update(stage, epoch, settings.epochs(), loss, validation, message);
        } catch (RuntimeException ignored) {
            // A broken observer must never fail a training run.
        }
    }

    private void checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new TrainingException("Обучение отменено.");
    }

    public Result run(Path dataset, Path output) throws IOException {
        if (Files.exists(output.resolve("manifest.json"))) throw new TrainingException("В папке " + output + " уже есть модель.");
        for (String head : settings.heads()) {
            if (!HEAD_FAMILIES.containsKey(head)) throw new TrainingException("Неизвестный выход модели " + head);
        }
        if (!settings.heads().contains("overall")) throw new TrainingException("Нужен выход overall.");
        long started = System.currentTimeMillis();
        notify("auditing", 0, Double.NaN, Double.NaN, "Проверка записей");
        List<TrainingDataset.Audit> audits = TrainingDataset.audit(dataset);
        List<TrainingSession> sessions = new ArrayList<>();
        int unsupportedFamily = 0;
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        for (TrainingDataset.Audit audit : audits) {
            verdicts.merge(audit.verdict().name(), 1, Integer::sum);
            TrainingSession session = audit.session();
            if (session == null || !("LEGIT".equals(session.label()) || session.cheat())) continue;
            boolean accepted = audit.verdict() == TrainingDataset.Verdict.GOOD
                    || ((settings.includeReview() || settings.allowSynthetic()) && audit.verdict() == TrainingDataset.Verdict.REVIEW);
            if (!accepted) continue;
            if (session.cheat() && !headPositive("aimAssist", session)) { unsupportedFamily++; continue; }
            sessions.add(session);
        }
        BundleWriter.writeJson(output.resolve("dataset_audit.json"), auditJson(audits));
        if (unsupportedFamily > 0) {
            warnings.add("Пропущено записей с читами не про прицел (killaura и т.п.): " + unsupportedFamily
                    + ". Эта модель учится отличать честную игру от аим-ассиста.");
        }
        boolean synthetic = sessions.stream().anyMatch(TrainingSession::synthetic);
        if (synthetic && !settings.allowSynthetic()) {
            throw new TrainingException("В датасете есть синтетические записи; они годятся только для проверки (флаг synthetic).");
        }
        if (sessions.isEmpty()) {
            throw new TrainingException("Ни одна запись не подошла для обучения (принято 0 из " + audits.size()
                    + "; проверки: " + verdicts + "). Частые причины: " + topReasons(audits)
                    + ". Подробности в dataset_audit.json.");
        }
        int staffAdded = 0;
        if (settings.includeStaffReviews()) {
            List<TrainingSession> staff = TrainingDataset.staffReviews(dataset);
            sessions.addAll(staff);
            staffAdded = staff.size();
        }
        checkCancelled();

        int before = settings.sequenceLength() - 1 - Math.min(10, settings.sequenceLength() - 1);
        WindowSet windows = "attack".equals(settings.window())
                ? WindowSet.attack(sessions, before, settings.sequenceLength() - 1 - before)
                : WindowSet.continuous(sessions, settings.sequenceLength(), settings.stride());
        if (windows.size() == 0) throw new TrainingException("Нет ни одного полного окна боя: запишите более длинные бои.");
        boolean bySession = "session".equals(settings.split());
        int[] staffPlacement = {0, 0};
        GroupSplit split;
        try {
            split = split(windows, bySession, staffAdded > 0, staffPlacement);
        } catch (TrainingException tooFewPlayers) {
            if (!"auto".equals(settings.split()) || bySession) throw tooFewPlayers;
            // One admin recording himself against the bot is one player: a by-player split cannot
            // exist. Split by recording instead and say plainly what that costs.
            bySession = true;
            split = split(windows, true, staffAdded > 0, staffPlacement);
            warnings.add("Разных игроков мало, поэтому выборки разделены по записям, а не по игрокам: "
                    + "в тесте те же игроки, что и в обучении, и оценка качества завышена. "
                    + "Для честной оценки запишите хотя бы 4-5 разных игроков.");
        }
        BundleWriter.writeJson(output.resolve("split_manifest.json"), split.manifest);
        // Head labels and which heads are trainable.
        List<String> heads = settings.heads();
        List<String> published = new ArrayList<>();
        List<String> notTrained = new ArrayList<>();
        int[] train = split.fold("train");
        for (String head : heads) {
            int positives = 0;
            for (int row : train) if (headPositive(head, windows.sessionOf(row))) positives++;
            if (positives > 0 && positives < train.length) published.add(head); else notTrained.add(head);
        }
        if (!published.contains("overall")) throw new TrainingException("Для выхода overall нет обоих классов в обучающей выборке.");
        if (!notTrained.isEmpty()) warnings.add("Не обучены выходы без примеров обоих классов: " + notTrained);

        notify("preparing", 0, Double.NaN, Double.NaN, windows.describe());
        long estimated = (long) windows.size() * windows.length * ModelFeature.FEATURE_COUNT * 4L;
        long budget = settings.maxCacheBytes() > 0 ? settings.maxCacheBytes() : Runtime.getRuntime().maxMemory() / 3;
        if (estimated > budget) {
            throw new TrainingException("Окон слишком много для памяти сервера (~" + estimated / (1024 * 1024) + " МБ при лимите "
                    + budget / (1024 * 1024) + " МБ). Увеличьте -Xmx или обучайте на меньшем наборе.");
        }
        Map<String, float[][]> data = new LinkedHashMap<>();
        float[][] trainWindows = encode(windows, train);
        FeatureNormalizer normalizer = FeatureNormalizer.fit(Arrays.asList(trainWindows));
        List<String> neverSeen = normalizer.neverObserved();
        if (!neverSeen.isEmpty()) warnings.add("Каналы, ни разу не встреченные в обучении: " + neverSeen);
        for (float[] window : trainWindows) normalizer.apply(window);
        data.put("train", trainWindows);
        for (String fold : List.of("validation", "calibration", "test")) {
            float[][] encoded = encode(windows, split.fold(fold));
            for (float[] window : encoded) normalizer.apply(window);
            data.put(fold, encoded);
        }
        Map<String, double[][]> targets = new HashMap<>();
        for (String fold : GroupSplit.FOLDS) {
            int[] rows = split.fold(fold);
            double[][] values = new double[rows.length][published.size()];
            for (int i = 0; i < rows.length; i++) {
                for (int h = 0; h < published.size(); h++) values[i][h] = headPositive(published.get(h), windows.sessionOf(rows[i])) ? 1 : 0;
            }
            targets.put(fold, values);
        }
        checkCancelled();

        int width = "pro".equals(settings.preset()) ? 128 : 64;
        int blocks = "pro".equals(settings.preset()) ? 6 : 4;
        TrainableTcn model = new TrainableTcn(ModelFeature.FEATURE_COUNT, windows.length, width, blocks, 3, 8,
                settings.dropout(), published, settings.seed());
        notify("preparing", 0, Double.NaN, Double.NaN, "Модель " + settings.preset() + ": " + model.parameterCount()
                + " параметров, окно " + windows.length);

        // Per-window weights: player balance, then class balance on the same weights.
        double[] weights = groupBalance(windows, train, settings.groupBalance());
        double[] positiveWeight = new double[published.size()];
        double weightSum = 0;
        for (double weight : weights) weightSum += weight;
        for (int h = 0; h < published.size(); h++) {
            double positives = 0;
            for (int i = 0; i < train.length; i++) positives += targets.get("train")[i][h] * weights[i];
            positives = Math.max(positives, 1e-6);
            positiveWeight[h] = Math.min((weightSum - positives) / positives, 1e4);
        }
        WindowAugmenter augmenter = settings.augment()
                ? new WindowAugmenter(normalizer, settings.mirrorProbability(), settings.channelDropout()) : null;

        int threads = Math.max(1, settings.threads());
        ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "Aero-trainer");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        try {
            TrainableTcn.Workspace[] spaces = new TrainableTcn.Workspace[threads];
            double[][] grads = new double[threads][model.parameterCount()];
            for (int i = 0; i < threads; i++) spaces[i] = model.workspace();
            AdamW optimizer = new AdamW(model.params.length, settings.weightDecay());

            double[] bestState = null;
            double[] bestScore = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            int patience = 0;
            List<Map<String, Object>> history = new ArrayList<>();
            SplittableRandom shuffler = new SplittableRandom(settings.seed());
            int overall = published.indexOf("overall");
            int[] validationLabels = labels(targets.get("validation"), overall);
            for (int epoch = 0; epoch < settings.epochs(); epoch++) {
                checkCancelled();
                double lr = settings.learningRate() * (1 + Math.cos(Math.PI * epoch / Math.max(1, settings.epochs()))) / 2;
                int[] order = permutation(train.length, shuffler);
                double total = 0;
                for (int from = 0; from < order.length; from += settings.batchSize()) {
                    checkCancelled();
                    int to = Math.min(order.length, from + settings.batchSize());
                    total += step(model, spaces, grads, pool, order, from, to, trainWindows, targets.get("train"), weights,
                            positiveWeight, augmenter, optimizer, lr, epoch);
                }
                double[][] validation = score(model, spaces, pool, data.get("validation"));
                double[] validationScores = column(validation, overall);
                double auc = Metrics.rocAuc(validationLabels, validationScores);
                double partial = Metrics.partialAuc(validationLabels, validationScores, settings.selectionMaxFpr());
                double validationLoss = binaryCrossEntropy(validationScores, validationLabels);
                double trainLoss = total / Math.max(1, order.length);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("epoch", epoch);
                entry.put("loss", finite(trainLoss));
                entry.put("validationRocAuc", finite(auc));
                entry.put("validationPartialAuc", finite(partial));
                entry.put("selectionMaxFpr", settings.selectionMaxFpr());
                entry.put("validationLoss", finite(validationLoss));
                history.add(entry);
                notify("training", epoch, trainLoss, validationLoss, String.format(Locale.ROOT,
                        "эпоха %d/%d: loss %.4f, ROC-AUC %.4f, pAUC %.4f", epoch + 1, settings.epochs(), trainLoss, auc, partial));
                double[] score = {Double.isFinite(partial) ? partial : Double.NEGATIVE_INFINITY,
                        Double.isFinite(auc) ? auc : Double.NEGATIVE_INFINITY};
                if (score[0] > bestScore[0] || (score[0] == bestScore[0] && score[1] > bestScore[1])) {
                    bestScore = score;
                    patience = 0;
                    bestState = model.params.clone();
                } else if (++patience >= settings.earlyStoppingPatience()) {
                    notify("training", epoch, trainLoss, validationLoss, "ранняя остановка на эпохе " + (epoch + 1));
                    break;
                }
            }
            if (bestState == null || !Double.isFinite(bestScore[1])) {
                throw new TrainingException("Нет конечной оценки на проверочной выборке; модель выбрать нельзя.");
            }
            System.arraycopy(bestState, 0, model.params, 0, bestState.length);

            notify("calibrating", settings.epochs(), Double.NaN, Double.NaN, "Калибровка");
            double[][] calibrationScores = score(model, spaces, pool, data.get("calibration"));
            Calibration.Scaler[] scalers = new Calibration.Scaler[published.size()];
            double[] priors = new double[published.size()];
            boolean calibrated = true;
            try {
                for (int h = 0; h < published.size(); h++) {
                    double[] logits = new double[calibrationScores.length];
                    double[] labels = new double[calibrationScores.length];
                    double positives = 0;
                    for (int i = 0; i < logits.length; i++) {
                        logits[i] = Calibration.logit(calibrationScores[i][h]);
                        labels[i] = targets.get("calibration")[i][h];
                        positives += labels[i];
                    }
                    priors[h] = positives / Math.max(1, labels.length);
                    scalers[h] = Calibration.fit(logits, labels);
                    if (!scalers[h].improved()) calibrated = false;
                }
            } catch (IllegalArgumentException error) {
                calibrated = false;
                warnings.add("Калибровка пропущена: " + error.getMessage());
            }
            if (!calibrated) warnings.add("Калибровка не улучшила правдоподобие; модель сохранена без калибровки.");
            Map<String, Object> calibration = calibrated ? Calibration.toJson(published, scalers, priors) : null;

            notify("evaluating", settings.epochs(), Double.NaN, Double.NaN, "Оценка на тестовой выборке");
            Map<String, Object> results = new LinkedHashMap<>();
            for (String fold : List.of("validation", "test")) {
                double[][] scores = score(model, spaces, pool, data.get(fold));
                double[] overallScores = new double[scores.length];
                for (int i = 0; i < scores.length; i++) {
                    overallScores[i] = calibrated ? scalers[overall].apply(Calibration.logit(scores[i][overall])) : scores[i][overall];
                }
                results.put(fold, Metrics.evaluate(labels(targets.get(fold), overall), overallScores, legitHours(windows, split.fold(fold))));
            }

            notify("exporting", settings.epochs(), Double.NaN, Double.NaN, "Сохранение модели");
            String version = "aero-" + settings.preset() + "-" + settings.window() + "-"
                    + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-java";
            Map<String, Object> weightsSummary = BundleWriter.writeWeights(model, model.params, settings.dropout(), output);
            Map<String, Object> evaluation = new LinkedHashMap<>();
            evaluation.put("folds", results);
            evaluation.put("history", history);
            evaluation.put("splitSizes", split.sizes());
            evaluation.put("splitBy", bySession ? "session" : "player");
            evaluation.put("headsNotTrained", notTrained);
            evaluation.put("channelsNeverObserved", neverSeen);
            Map<String, Object> lineage = new LinkedHashMap<>();
            lineage.put("normalizationFold", "train");
            lineage.put("epochSelectionFold", "validation");
            lineage.put("calibrationFold", "calibration");
            evaluation.put("lineage", lineage);
            Map<String, Object> staff = new LinkedHashMap<>();
            staff.put("added", staffPlacement[0]);
            staff.put("droppedForLeakage", staffPlacement[1]);
            evaluation.put("staffReviewedWindows", staff);
            evaluation.put("synthetic", synthetic);
            evaluation.put("purpose", synthetic ? "pipeline-smoke-only" : "candidate-awaiting-human-promotion-review");
            evaluation.put("unknownClientBenchmark", "unknown-client".equals(split.manifest.get("strategy"))
                    ? "held-out" : "UNKNOWN CLIENT GENERALIZATION NOT MEASURABLE");
            evaluation.put("javaWeights", weightsSummary);
            evaluation.put("warnings", warnings);
            evaluation.put("trainingSeconds", (System.currentTimeMillis() - started) / 1000);
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("datasetVersion", "dataset-v1");
            provenance.put("datasetRoot", dataset.toString());
            provenance.put("gitCommit", "");
            provenance.put("trainingConfig", settings.toJson(dataset, output));
            provenance.put("splitManifest", split.manifest);
            provenance.put("evaluation", evaluation);
            provenance.put("created", ZonedDateTime.now(ZoneOffset.UTC).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            provenance.put("notes", "trained in the plugin (Java trainer)");
            BundleWriter.writeManifest(output, version, settings.preset(), settings.window(), windows.length, published,
                    normalizer, calibration, provenance);
            notify("completed", settings.epochs(), Double.NaN, Double.NaN, "Готово: " + version);
            return new Result(output, version, results, calibrated, synthetic, List.copyOf(warnings));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Builds the folds and checks every fold holds both classes; throws with an operator-facing reason. */
    private GroupSplit split(WindowSet windows, boolean bySession, boolean staff, int[] staffPlacement) {
        GroupSplit split = GroupSplit.make(windows, settings.seed(), settings.holdoutClients(), warnings, bySession);
        int[] placed = staff ? split.trainOnly(windows) : new int[]{0, 0};
        staffPlacement[0] = placed[0];
        staffPlacement[1] = placed[1];
        split.verify(windows);
        for (String fold : GroupSplit.FOLDS) {
            int[] rows = split.fold(fold);
            if (rows.length == 0) throw new TrainingException("Выборка " + fold + " пуста: нужно больше записей.");
            boolean cheat = false, legit = false;
            for (int row : rows) { if (windows.label(row) == 1) cheat = true; else legit = true; }
            if (!cheat || !legit) {
                throw new TrainingException("В выборке " + fold + " нет и честных, и читерских боёв. "
                        + "Нужно больше записей: хотя бы 5 честных и 5 с читом" + (bySession ? "." : " от разных игроков."));
            }
        }
        return split;
    }

    /** One optimiser step on rows order[from, to). Returns the batch's summed loss (loss * batch size). */
    private double step(TrainableTcn model, TrainableTcn.Workspace[] spaces, double[][] grads, ExecutorService pool,
                        int[] order, int from, int to, float[][] windows, double[][] targets, double[] weights,
                        double[] positiveWeight, WindowAugmenter augmenter, AdamW optimizer, double lr, int epoch) {
        int count = to - from;
        double weightSum = 0;
        for (int i = from; i < to; i++) weightSum += weights[order[i]];
        double normaliser = Math.max(weightSum, 1e-12);
        int threads = spaces.length;
        int chunk = (count + threads - 1) / threads;
        List<Future<Double>> futures = new ArrayList<>();
        for (int worker = 0; worker < threads; worker++) {
            int start = from + worker * chunk, end = Math.min(to, start + chunk);
            double[] grad = grads[worker];
            Arrays.fill(grad, 0);
            if (start >= end) continue;
            TrainableTcn.Workspace space = spaces[worker];
            futures.add(pool.submit(() -> {
                double loss = 0;
                int heads = positiveWeight.length;
                double[] dLogits = new double[heads];
                for (int i = start; i < end; i++) {
                    int row = order[i];
                    // Per-sample randomness keyed by (seed, epoch, row): the same run gives the same model
                    // whatever the thread count.
                    SplittableRandom random = new SplittableRandom(settings.seed() * 1_000_003L + epoch * 7_919L * 1_000_003L + row);
                    float[] input = augmenter == null ? windows[row] : augmenter.apply(windows[row], random);
                    double[] logits = model.forward(space, input, random);
                    double perWindow = 0;
                    for (int h = 0; h < heads; h++) {
                        double z = logits[h], y = targets[row][h], pw = positiveWeight[h];
                        double lw = 1 + (pw - 1) * y;
                        perWindow += (1 - y) * z + lw * (Math.log1p(Math.exp(-Math.abs(z))) + Math.max(-z, 0));
                        double sigma = Calibration.sigmoid(z);
                        dLogits[h] = (sigma * lw + (1 - y) - lw) * weights[row] / normaliser / heads;
                    }
                    loss += perWindow / heads * weights[row];
                    model.backward(space, dLogits, grad);
                }
                return loss;
            }));
        }
        double loss = 0;
        try {
            for (Future<Double> future : futures) loss += future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new TrainingException("Обучение прервано.");
        } catch (ExecutionException error) {
            throw new IllegalStateException(error.getCause());
        }
        double[] total = grads[0];
        for (int worker = 1; worker < threads; worker++) {
            double[] other = grads[worker];
            for (int i = 0; i < total.length; i++) total[i] += other[i];
        }
        // clip_grad_norm_(5.0)
        double norm = 0;
        for (double value : total) norm += value * value;
        norm = Math.sqrt(norm);
        if (!Double.isFinite(norm)) throw new TrainingException("Обучение разошлось (градиент не конечен).");
        if (norm > 5.0) {
            double scale = 5.0 / (norm + 1e-6);
            for (int i = 0; i < total.length; i++) total[i] *= scale;
        }
        optimizer.step(model.params, total, lr);
        return loss / normaliser * count;
    }

    /** Sigmoid probabilities per head, model in evaluation mode (no dropout). */
    private double[][] score(TrainableTcn model, TrainableTcn.Workspace[] spaces, ExecutorService pool, float[][] windows) {
        double[][] out = new double[windows.length][];
        int threads = spaces.length;
        int chunk = (windows.length + threads - 1) / Math.max(1, threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int worker = 0; worker < threads; worker++) {
            int start = worker * chunk, end = Math.min(windows.length, start + chunk);
            if (start >= end) continue;
            TrainableTcn.Workspace space = spaces[worker];
            futures.add(pool.submit(() -> {
                for (int i = start; i < end; i++) {
                    double[] logits = model.forward(space, windows[i], null);
                    double[] probabilities = new double[logits.length];
                    for (int h = 0; h < logits.length; h++) probabilities[h] = Calibration.sigmoid(logits[h]);
                    out[i] = probabilities;
                }
            }));
        }
        try {
            for (Future<?> future : futures) future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new TrainingException("Обучение прервано.");
        } catch (ExecutionException error) {
            throw new IllegalStateException(error.getCause());
        }
        return out;
    }

    private static float[][] encode(WindowSet windows, int[] rows) {
        float[][] out = new float[rows.length][];
        for (int i = 0; i < rows.length; i++) out[i] = windows.encode(rows[i]);
        return out;
    }

    /** Every player contributes n^(1 - power) in total instead of n; normalised to mean 1. */
    static double[] groupBalance(WindowSet windows, int[] rows, double power) {
        Map<String, Integer> counts = new HashMap<>();
        for (int row : rows) counts.merge(windows.attribute("player", row), 1, Integer::sum);
        double[] weights = new double[rows.length];
        double sum = 0;
        for (int i = 0; i < rows.length; i++) {
            weights[i] = Math.pow(counts.get(windows.attribute("player", rows[i])), -power);
            sum += weights[i];
        }
        double mean = sum / Math.max(1, rows.length);
        for (int i = 0; i < weights.length; i++) weights[i] = (float) (weights[i] / mean);
        return weights;
    }

    private static int[] permutation(int n, SplittableRandom random) {
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i]; order[i] = order[j]; order[j] = swap;
        }
        return order;
    }

    private static double[] column(double[][] values, int column) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i][column];
        return out;
    }

    private static int[] labels(double[][] values, int column) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (int) values[i][column];
        return out;
    }

    private static double binaryCrossEntropy(double[] probabilities, int[] labels) {
        double total = 0;
        for (int i = 0; i < labels.length; i++) {
            double p = Math.min(1 - 1e-7, Math.max(1e-7, probabilities[i]));
            total += labels[i] == 1 ? -Math.log(p) : -Math.log(1 - p);
        }
        return total / Math.max(1, labels.length);
    }

    private static double legitHours(WindowSet windows, int[] rows) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        double hours = 0;
        for (int row : rows) {
            TrainingSession session = windows.sessionOf(row);
            if (session.cheat() || !seen.add(session.sessionId())) continue;
            hours += session.combatSeconds() / 3600.0;
        }
        return hours;
    }

    private static Object finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    /** The three commonest reasons recordings were not GOOD, for the error an operator reads. */
    static String topReasons(List<TrainingDataset.Audit> audits) {
        Map<String, Integer> counts = new HashMap<>();
        for (TrainingDataset.Audit audit : audits) {
            for (String reason : audit.reasons()) counts.merge(reason.replaceAll(":.*", ""), 1, Integer::sum);
        }
        if (counts.isEmpty()) return "нет записей с меткой LEGIT или CHEAT";
        return counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")").collect(java.util.stream.Collectors.joining(", "));
    }

    private static Map<String, Object> auditJson(List<TrainingDataset.Audit> audits) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> sessions = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TrainingDataset.Audit audit : audits) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sessionId", audit.sessionId());
            entry.put("label", audit.label());
            entry.put("verdict", audit.verdict().name());
            entry.put("reasons", audit.reasons());
            sessions.add(entry);
            counts.merge(audit.verdict().name(), 1, Integer::sum);
        }
        report.put("featureSchemaVersion", FeatureEncoder.FEATURE_SCHEMA_VERSION);
        report.put("sessionsTotal", audits.size());
        report.put("verdicts", counts);
        report.put("sessions", sessions);
        return report;
    }

    /** torch.optim.AdamW defaults: betas (0.9, 0.999), eps 1e-8, decoupled weight decay. */
    static final class AdamW {
        private final double[] m, v;
        private final double weightDecay;
        private int step;

        AdamW(int size, double weightDecay) {
            m = new double[size];
            v = new double[size];
            this.weightDecay = weightDecay;
        }

        void step(double[] params, double[] grad, double lr) {
            step++;
            double beta1 = 0.9, beta2 = 0.999, eps = 1e-8;
            double correction1 = 1 - Math.pow(beta1, step), correction2 = 1 - Math.pow(beta2, step);
            double stepSize = lr / correction1;
            double root = Math.sqrt(correction2);
            for (int i = 0; i < params.length; i++) {
                params[i] *= 1 - lr * weightDecay;
                m[i] = beta1 * m[i] + (1 - beta1) * grad[i];
                v[i] = beta2 * v[i] + (1 - beta2) * grad[i] * grad[i];
                params[i] -= stepSize * m[i] / (Math.sqrt(v[i]) / root + eps);
            }
        }
    }
}
