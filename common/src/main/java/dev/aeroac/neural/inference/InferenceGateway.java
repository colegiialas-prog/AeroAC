package dev.aeroac.neural.inference;

import dev.aeroac.neural.NeuralConfig;
import dev.aeroac.neural.NeuralPlayerState;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.CombatTelemetryCollector;
import dev.aeroac.player.AeroPlayer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides when a window is worth an inference and hands the result back on the player event loop.
 * The packet path never waits: a window is encoded, offered to the client and forgotten. When the
 * client is saturated, or the service is down, the request is simply skipped.
 */
public final class InferenceGateway {
    private final NeuralConfig.Inference config;
    private final InferenceClient client;
    private final ResultHandler handler;
    private final AtomicLong requestIds = new AtomicLong();
    private final long minIntervalNanos;
    private final long proIntervalNanos;

    /** Invoked on the player event loop with a result that still matches the live collector. */
    public interface ResultHandler {
        void accept(AeroPlayer player, InferenceRequest request, PredictionResult result);
    }

    public InferenceGateway(NeuralConfig.Inference config, InferenceClient client, ResultHandler handler) {
        this.config = config;
        this.client = client;
        this.handler = handler;
        this.minIntervalNanos = config.minIntervalMs() * 1_000_000L;
        this.proIntervalNanos = config.proMinIntervalMs() * 1_000_000L;
    }

    public InferenceClient client() { return client; }
    public NeuralConfig.Inference config() { return config; }

    /** Called right after a sample landed, on the player event loop. */
    public void afterSample(AeroPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector, long nowNanos) {
        if (nowNanos - state.lastFlashNanos >= minIntervalNanos) {
            CombatFrame[] window = window(collector, config.flashWindow(), config.flashSequence());
            if (window != null && request(player, state, collector, ModelKind.FLASH, config.flashWindow(), window, nowNanos)) {
                state.lastFlashNanos = nowNanos;
            }
        }
        if (config.proEnabled() && escalate(state, nowNanos)) {
            CombatFrame[] window = window(collector, config.proWindow(), config.proSequence());
            if (window != null && request(player, state, collector, ModelKind.PRO, config.proWindow(), window, nowNanos)) {
                state.lastProNanos = nowNanos;
            }
        }
    }

    /** Pro runs on Flash's judgement, not on its own schedule, so the expensive model stays rare. */
    private boolean escalate(NeuralPlayerState state, long nowNanos) {
        if (nowNanos - state.lastProNanos < proIntervalNanos) return false;
        PredictionResult latest = state.trail == null ? null : state.trail.latest();
        return latest != null && latest.model() == ModelKind.FLASH && latest.overall() >= config.proTrigger();
    }

    private CombatFrame[] window(CombatTelemetryCollector collector, ModelWindow type, int length) {
        return type == ModelWindow.ATTACK ? collector.completedAttackWindow() : collector.continuousWindow(length);
    }

    private boolean request(AeroPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector,
                            ModelKind kind, ModelWindow window, CombatFrame[] frames, long nowNanos) {
        InferenceRequest request;
        try {
            request = InferenceRequest.of(requestIds.incrementAndGet(), kind, window, FeatureEncoder.encode(frames),
                    frames.length, frames[frames.length - 1].nanoTime(), collector.generation());
        } catch (RuntimeException malformed) {
            client.health().rejected("window encode: " + malformed.getMessage());
            return false;
        }
        // Built first, admitted second: the permit infer() releases is only ever taken for a real send.
        if (!client.admit()) {
            state.inferenceSkipped++;
            return false;
        }
        client.infer(request).whenComplete((response, error) -> {
            if (error != null || response == null) return;
            long arrival = System.nanoTime();
            PredictionResult result = PredictionResult.of(response, arrival, (arrival - nowNanos) / 1_000_000L);
            player.runSafely(() -> {
                // A new collector can belong to the same config generation after idle/disconnect.
                if (!state.disconnected && state.collector == collector) handler.accept(player, request, result);
            });
        });
        return true;
    }
}
