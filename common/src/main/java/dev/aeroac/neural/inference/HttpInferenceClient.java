package dev.aeroac.neural.inference;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * First production transport. Every step after the caller hands over the request runs on this
 * client's own threads: body encoding, the HTTP exchange and decoding. Nothing here touches
 * AeroPlayer, and no caller ever waits on the returned future.
 */
public final class HttpInferenceClient implements InferenceClient {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final URI endpoint;
    private final Duration timeout;
    private final int maxInFlight;
    private final ExecutorService executor;
    private final HttpClient http;
    private final InferenceHealth health = new InferenceHealth();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong threadIds = new AtomicLong();
    private volatile boolean closed;

    public HttpInferenceClient(String endpoint, int timeoutMs, int maxInFlight, int threads) {
        this.endpoint = URI.create(endpoint);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.maxInFlight = maxInFlight;
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads), task -> {
            Thread thread = new Thread(task, "Aero-neural-inference-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

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
            result = CompletableFuture
                    .supplyAsync(() -> InferenceJson.encode(request), executor)
                    .thenCompose(body -> exchange(body, request))
                    .thenApply(body -> decode(body, request));
        } catch (RuntimeException rejected) {
            // A shutting-down executor rejects synchronously; the permit must still come back,
            // and the caller must get a completed future rather than an exception on the packet path.
            inFlight.decrementAndGet();
            record(rejected);
            return CompletableFuture.failedFuture(rejected);
        }
        return result.whenComplete((response, error) -> {
            inFlight.decrementAndGet();
            if (error != null) record(error);
            else health.accepted((System.nanoTime() - started) / 1_000_000L);
        });
    }

    private CompletionStage<String> exchange(String body, InferenceRequest request) {
        HttpRequest post = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Aero-Protocol", Integer.toString(InferenceRequest.PROTOCOL_VERSION))
                .header("X-Aero-Feature-Schema", Integer.toString(request.featureSchemaVersion()))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(post, response -> new BoundedBody()).thenApply(response -> {
            int status = response.statusCode();
            if (status == 200) return response.body();
            // 409/422 mean the service refused this schema; retrying the same payload cannot help.
            throw new InferenceServiceException("HTTP " + status, status == 409 || status == 422 || status == 429);
        });
    }

    /** Any parse failure is the service's fault, so it is a refusal to retry rather than a transport error. */
    private static InferenceResponse decode(String body, InferenceRequest request) {
        try {
            return InferenceJson.decode(body, request);
        } catch (RuntimeException malformed) {
            throw new InferenceServiceException("malformed response: " + malformed.getMessage(), true);
        }
    }

    private void record(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
            health.timedOut();
        } else if (cause instanceof InferenceServiceException serviceError && serviceError.refused()) {
            health.rejected(cause.getMessage());
        } else {
            health.failed(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
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

    /** Marks a refusal the service will repeat for an identical payload. */
    public static final class InferenceServiceException extends RuntimeException {
        private final boolean refused;

        public InferenceServiceException(String message, boolean refused) {
            super(message, null, false, false);
            this.refused = refused;
        }

        public boolean refused() { return refused; }
    }

    /** Caps an answer from a misconfigured or hostile endpoint instead of buffering it without limit. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final List<ByteBuffer> chunks = new ArrayList<>();
        private Flow.Subscription subscription;
        private int size;

        @Override public CompletionStage<String> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                size += buffer.remaining();
                if (size > MAX_RESPONSE_BYTES) {
                    chunks.clear();
                    subscription.cancel();
                    body.completeExceptionally(new InferenceServiceException("Response over " + MAX_RESPONSE_BYTES + " bytes", true));
                    return;
                }
                chunks.add(buffer);
            }
        }

        @Override public void onError(Throwable error) { body.completeExceptionally(error); }

        @Override public void onComplete() {
            if (body.isDone()) return;
            byte[] bytes = new byte[size];
            int offset = 0;
            for (ByteBuffer chunk : chunks) {
                int length = chunk.remaining();
                chunk.get(bytes, offset, length);
                offset += length;
            }
            body.complete(new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
