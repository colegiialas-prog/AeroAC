package dev.aeroac.neural.inference.local;

import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.inference.HttpInferenceClient.InferenceServiceException;
import dev.aeroac.neural.inference.InferenceClient;
import dev.aeroac.neural.inference.InferenceHealth;
import dev.aeroac.neural.inference.InferenceRequest;
import dev.aeroac.neural.inference.InferenceResponse;
import dev.aeroac.neural.inference.ModelKind;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the bundled temporal ConvNet inside the JVM: no Python service, no network, no JSON.
 * Same contract as the HTTP client: the packet thread only submits, a small daemon pool computes,
 * the answer comes back as the same InferenceResponse and is applied through runSafely.
 *
 * <p>A Flash window is a few million multiply-adds, around a millisecond on one core. The in-flight
 * cap sheds instead of queueing, exactly as the remote client does, so a burst of fights can delay
 * a prediction but never the server.
 */
public final class LocalInferenceClient implements InferenceClient {
    private final Map<ModelKind, LocalModelBundle> models;
    private final int maxInFlight;
    private final ExecutorService executor;
    private final InferenceHealth health = new InferenceHealth();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong threadIds = new AtomicLong();
    private volatile boolean closed;

    public LocalInferenceClient(List<LocalModelBundle> bundles, int maxInFlight, int threads) {
        Map<ModelKind, LocalModelBundle> byKind = new EnumMap<>(ModelKind.class);
        for (LocalModelBundle bundle : bundles) {
            if (byKind.put(bundle.kind(), bundle) != null) {
                throw new IllegalArgumentException("two local bundles for " + bundle.kind().wireName());
            }
        }
        if (byKind.isEmpty()) throw new IllegalArgumentException("no local model bundle");
        this.models = byKind;
        this.maxInFlight = Math.max(1, maxInFlight);
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads), task -> {
            Thread thread = new Thread(task, "Aero-neural-local-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    public LocalModelBundle model(ModelKind kind) { return models.get(kind); }

    @Override public boolean admit() {
        if (closed) return false;
        while (true) {
            int current = inFlight.get();
            if (current >= maxInFlight) {
                health.shed();
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) return true;
        }
    }

    /** Must be preceded by an accepted admit(); the permit is released when this future completes. */
    @Override public CompletableFuture<InferenceResponse> infer(InferenceRequest request) {
        health.sent();
        long started = System.nanoTime();
        CompletableFuture<InferenceResponse> result;
        try {
            result = CompletableFuture.supplyAsync(() -> run(request), executor);
        } catch (RuntimeException rejected) {
            inFlight.decrementAndGet();
            health.failed(rejected.getClass().getSimpleName() + ": " + rejected.getMessage());
            return CompletableFuture.failedFuture(rejected);
        }
        return result.whenComplete((response, error) -> {
            inFlight.decrementAndGet();
            if (error == null) {
                health.accepted((System.nanoTime() - started) / 1_000_000L);
                return;
            }
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            if (cause instanceof InferenceServiceException refusal && refusal.refused()) health.rejected(cause.getMessage());
            else health.failed(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        });
    }

    private InferenceResponse run(InferenceRequest request) {
        LocalModelBundle model = models.get(request.model());
        if (model == null) throw new InferenceServiceException("no local " + request.model().wireName() + " model", true);
        if (request.featureSchemaVersion() != FeatureEncoder.FEATURE_SCHEMA_VERSION) {
            throw new InferenceServiceException("feature schema " + request.featureSchemaVersion(), true);
        }
        if (request.sequenceLength() != model.sequenceLength() || request.window() != model.window()) {
            throw new InferenceServiceException("model expects a " + model.window() + " window of "
                    + model.sequenceLength() + ", got " + request.window() + " of " + request.sequenceLength(), true);
        }
        double[] values = model.predict(request.features());
        return new InferenceResponse(request.requestId(), InferenceRequest.PROTOCOL_VERSION,
                FeatureEncoder.FEATURE_SCHEMA_VERSION, model.modelVersion(), request.model(), model.calibrated(),
                model.heads().toArray(new String[0]), values, model.calibrationPrior());
    }

    @Override public InferenceHealth health() { return health; }

    @Override public int inFlight() { return inFlight.get(); }

    @Override public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
