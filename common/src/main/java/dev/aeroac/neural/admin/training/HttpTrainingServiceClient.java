package dev.aeroac.neural.admin.training;

import dev.aeroac.neural.inference.FeatureEncoder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Bounded background transport. Every getter is cached; closing invalidates all outstanding replies. */
public final class HttpTrainingServiceClient implements TrainingServiceClient {
    private record Cached(TrainingJob job, EvaluationSummary candidate, String problem) { }
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Gson JSON = new Gson();
    private final String endpoint;
    private final String token;
    private final int timeoutMs;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean action = new AtomicBoolean();
    private volatile boolean closed;
    private volatile Cached cached = new Cached(TrainingJob.disconnected("Ожидание сервиса обучения."),
            EvaluationSummary.NONE, "Ожидание сервиса обучения.");
    private String resultJob;

    public HttpTrainingServiceClient(String endpoint, int timeoutMs, String token) {
        URI uri = URI.create(endpoint);
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Недопустимый адрес сервиса обучения.");
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.timeoutMs = Math.max(100, Math.min(30000, timeoutMs));
        this.token = token == null ? "" : token;
        if (this.token.indexOf('\r') >= 0 || this.token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Недопустимый токен сервиса обучения.");
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Aero-training-http");
            thread.setDaemon(true); return thread;
        });
        worker.scheduleWithFixedDelay(this::poll, 0, 2, TimeUnit.SECONDS);
    }

    @Override public TrainingJob job() { return cached.job(); }
    @Override public EvaluationSummary currentEvaluation() { return EvaluationSummary.NONE; }
    @Override public EvaluationSummary candidateEvaluation() { return cached.candidate(); }
    @Override public boolean configured() { return !closed; }
    @Override public String unavailableReason() { return cached.problem(); }

    @Override public void poll() {
        if (closed || !polling.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { refresh(); }
                catch (Exception error) { failure(error); }
                finally { polling.set(false); }
            });
        } catch (RuntimeException stopped) { polling.set(false); }
    }

    private void refresh() throws IOException {
        exchange("GET", "/health", null);
        TrainingJob job = parseJob(exchange("GET", "/training/status", null));
        EvaluationSummary candidate = cached.candidate();
        if (job.status() == TrainingJob.Status.COMPLETED && job.jobId() != null && !job.jobId().equals(resultJob)) {
            candidate = parseEvaluation(exchange("GET", jobPath(job.jobId()) + "/result", null));
            resultJob = job.jobId();
        }
        publish(new Cached(job, candidate, null));
    }

    @Override public void start(TrainingRequest request, Consumer<String> reply) {
        submit(() -> {
            TrainingJob job = parseJob(exchange("POST", "/training/jobs", JSON.toJson(request)));
            publish(new Cached(job, cached.candidate(), null));
            return "Задача обучения принята: " + job.jobId() + ". " + job.message();
        }, reply);
    }

    @Override public void cancel(Consumer<String> reply) {
        String id = cached.job().jobId();
        if (id == null || !cached.job().running()) { reply.accept("Нет активной задачи обучения для отмены."); return; }
        submit(() -> {
            exchange("POST", jobPath(id) + "/cancel", "{}");
            refresh();
            return "Запрос отмены задачи отправлен: " + id;
        }, reply);
    }

    private interface Operation { String run() throws IOException; }
    private void submit(Operation operation, Consumer<String> reply) {
        if (closed) { reply.accept("Конфигурация изменилась. Откройте центр обучения заново."); return; }
        if (!action.compareAndSet(false, true)) { reply.accept("Предыдущий запрос ещё выполняется."); return; }
        try {
            worker.execute(() -> {
                try {
                    if (!closed) {
                        String message = operation.run();
                        if (!closed) reply.accept(message);
                    }
                } catch (Exception error) {
                    if (!closed) reply.accept("Запрос обучения отклонён. Причина: " + reason(error));
                } finally { action.set(false); }
            });
        } catch (RuntimeException stopped) {
            action.set(false); reply.accept("Клиент обучения остановлен.");
        }
    }

    private synchronized void publish(Cached next) { if (!closed) cached = next; }
    private void failure(Exception error) {
        String message = "Сервис обучения недоступен. Сбор данных и детерминированные проверки продолжают работать. "
                + endpoint + ": " + reason(error);
        publish(new Cached(TrainingJob.disconnected(message), cached.candidate(), message));
    }
    private static String reason(Exception error) {
        if (error instanceof java.net.ConnectException) return "Соединение отклонено.";
        if (error instanceof java.net.SocketTimeoutException) return "Превышено время ожидания.";
        if (error instanceof java.net.UnknownHostException) return "Адрес сервиса не найден.";
        return error.getMessage() == null ? "Не удалось обработать ответ сервиса." : error.getMessage();
    }
    private static String jobPath(String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Недопустимый идентификатор задачи.");
        return "/training/jobs/" + id;
    }

    private JsonObject exchange(String method, String path, String body) throws IOException {
        if (closed) throw new IOException("Клиент обучения остановлен.");
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint + path).toURL().openConnection();
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            byte[] bytes;
            try (var input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                bytes = input == null ? new byte[0] : input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw new IOException("Ответ сервиса превышает допустимый размер.");
            JsonObject data;
            try { data = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException invalid) { throw new IOException("Некорректный JSON сервиса; HTTP " + status); }
            if (status < 200 || status >= 300) {
                String message = JsonFiles.text(data, "message");
                throw new IOException(message == null ? "Сервис отклонил запрос: HTTP " + status : message);
            }
            return data;
        } finally { connection.disconnect(); }
    }

    static TrainingJob parseJob(JsonObject data) {
        // POST /training/jobs returns an acknowledgement with the canonical job object nested
        // under "job". GET /training/status and GET /training/jobs/{id} return that object
        // directly. Accept both wire shapes so the start button can publish the accepted job
        // immediately instead of reporting a parse error after the service already queued it.
        if (data.has("job") && data.get("job").isJsonObject()) data = data.getAsJsonObject("job");
        TrainingJob.Status status;
        try {
            String wireStatus = JsonFiles.text(data, "status");
            if (wireStatus == null) wireStatus = JsonFiles.text(data, "javaStatus");
            status = TrainingJob.Status.valueOf(wireStatus);
        }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Неизвестное состояние задачи обучения."); }
        int epoch = Math.toIntExact(JsonFiles.integer(data, "epoch", 0));
        int total = Math.toIntExact(JsonFiles.integer(data, "totalEpochs", 0));
        double progress = metric(data, "progress", 0, 1, false);
        if (epoch < 0 || total < epoch)
            throw new IllegalArgumentException("Некорректный прогресс обучения.");
        List<String> heads = new ArrayList<>();
        if (data.has("heads")) {
            if (!data.get("heads").isJsonArray() || data.getAsJsonArray("heads").size() > 32)
                throw new IllegalArgumentException("Некорректный список выходов модели.");
            data.getAsJsonArray("heads").forEach(value -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Некорректный список выходов модели.");
                String head = value.getAsString();
                if (head.isBlank() || head.length() > 64)
                    throw new IllegalArgumentException("Некорректный список выходов модели.");
                heads.add(head);
            });
        }
        int schema = Math.toIntExact(JsonFiles.integer(data, "featureSchemaVersion", 0));
        if (schema != 0 && schema != FeatureEncoder.FEATURE_SCHEMA_VERSION) throw incompatibleSchema(schema);
        String jobId = JsonFiles.text(data, "jobId");
        if (jobId != null && !jobId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Недопустимый идентификатор задачи.");
        long elapsed = JsonFiles.integer(data, "elapsedSeconds", 0);
        long updated = JsonFiles.integer(data, "updatedAtMillis", 0);
        if (elapsed < 0 || updated < 0) throw new IllegalArgumentException("Некорректное время задачи обучения.");
        return new TrainingJob(status, JsonFiles.text(data, "jobId"), JsonFiles.text(data, "modelType"),
                JsonFiles.text(data, "datasetVersion"), schema, JsonFiles.text(data, "window"), String.join(", ", heads),
                epoch, total, progress, metric(data, "trainLoss", 0, Double.POSITIVE_INFINITY, true),
                metric(data, "validationLoss", 0, Double.POSITIVE_INFINITY, true), elapsed, updated,
                JsonFiles.text(data, "message"));
    }

    static EvaluationSummary parseEvaluation(JsonObject result) {
        JsonObject report = object(result, "evaluationReport");
        JsonObject metrics = object(report, "windowMetrics");
        JsonObject simulation = object(report, "riskSimulation");
        JsonObject detection = object(simulation, "detection");
        JsonObject falsePositives = object(simulation, "falsePositives");
        List<String> caveats = new ArrayList<>();
        if (JsonFiles.bool(result, "smokeOnly")) caveats.add("Синтетический тест pipeline. Не является оценкой качества античита.");
        if (report.size() == 0) caveats.add("Недостаточно данных для оценки модели.");
        strings(report, "limitations", caveats);
        caveats.add("Кандидат требует ручной проверки. Рабочая модель не заменена.");
        // Do not substitute validation metrics for independent test metrics or claim an unsupported FPR.
        JsonObject curve = object(metrics, "tprAtFpr");
        JsonObject point = object(curve, "0.001");
        boolean reliable = JsonFiles.integer(point, "reliable", 0) == 1
                && JsonFiles.integer(metrics, "positives", 0) > 0;
        JsonObject overall = object(detection, "overall");
        JsonObject known = object(detection, "knownClient");
        JsonObject unknown = object(detection, "unknownClient");
        long knownSessions = sessions(known);
        long unknownSessions = sessions(unknown);
        EvaluationSummary.Population population = knownSessions > 0 && unknownSessions == 0
                ? EvaluationSummary.Population.KNOWN_CLIENT
                : unknownSessions > 0 && knownSessions == 0
                ? EvaluationSummary.Population.UNKNOWN_CLIENT : EvaluationSummary.Population.MIXED;
        Map<String, Double> byPopulation = Map.of(
                "KNOWN_CLIENT", rate(known), "UNKNOWN_CLIENT", rate(unknown));
        JsonObject cheatBreakdowns = object(object(simulation, "breakdowns"), "cheat");
        int schema = Math.toIntExact(JsonFiles.integer(result, "featureSchemaVersion", 0));
        if (schema != 0 && schema != FeatureEncoder.FEATURE_SCHEMA_VERSION) throw incompatibleSchema(schema);
        String cohort = JsonFiles.text(report, "cohortId");
        if (report.size() > 0 && (cohort == null || !cohort.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("Отчёт оценки не содержит допустимый cohortId.");
        return new EvaluationSummary(JsonFiles.text(result, "modelVersion"), JsonFiles.text(result, "datasetVersion"),
                schema, metric(metrics, "rocAuc", 0, 1, true), metric(metrics, "prAuc", 0, 1, true),
                0.001, reliable ? metric(point, "tpr", 0, 1, false) : Double.NaN,
                metric(object(falsePositives, "perCombatHour"), "SUSPICIOUS", 0, Double.POSITIVE_INFINITY, true),
                metric(overall, "medianTimeToSuspiciousSeconds", 0, Double.POSITIVE_INFINITY, true),
                JsonFiles.bool(result, "calibrated") ? EvaluationSummary.Calibration.CALIBRATED : EvaluationSummary.Calibration.UNCALIBRATED,
                population, rates(object(cheatBreakdowns, "assistStrength")),
                rates(object(cheatBreakdowns, "clientFamily")), rates(object(cheatBreakdowns, "scenario")), caveats,
                JsonFiles.integer(result, "updatedAtMillis", 0), cohort, byPopulation);
    }

    private static double rate(JsonObject group) {
        return sessions(group) > 0 ? metric(group, "reachedSuspicious", 0, 1, false) : Double.NaN;
    }

    private static long sessions(JsonObject group) {
        long sessions = JsonFiles.integer(group, "sessions", 0);
        if (sessions < 0) throw new IllegalArgumentException("Некорректное число сессий в отчёте оценки.");
        return sessions;
    }

    private static double metric(JsonObject data, String key, double min, double max, boolean optional) {
        double value = JsonFiles.number(data, key);
        if (Double.isNaN(value)) {
            if (optional) return value;
            throw new IllegalArgumentException("В отчёте отсутствует обязательная метрика: " + key);
        }
        if (value < min || value > max) throw new IllegalArgumentException("Некорректная метрика: " + key);
        return value;
    }

    private static Map<String, Double> rates(JsonObject groups) {
        if (groups.size() == 0) return Map.of();
        if (groups.size() > 200) throw new IllegalArgumentException("Слишком много групп в отчёте оценки.");
        Map<String, Double> result = new java.util.HashMap<>();
        for (var entry : groups.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            result.put(entry.getKey(), rate(entry.getValue().getAsJsonObject()));
        }
        return result;
    }

    private static void strings(JsonObject source, String key, List<String> target) {
        if (!source.has(key) || !source.get(key).isJsonArray()) return;
        for (var item : source.getAsJsonArray(key)) {
            if (target.size() >= 12 || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) break;
            String value = item.getAsString();
            target.add(value.substring(0, Math.min(400, value.length())));
        }
    }
    private static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
    }
    @Override public synchronized void close() { closed = true; worker.shutdownNow(); }

    private static IllegalArgumentException incompatibleSchema(int schema) {
        return new IllegalArgumentException("Версия данных несовместима. Ожидается: v"
                + FeatureEncoder.FEATURE_SCHEMA_VERSION + ". Получено: v" + schema);
    }
}
