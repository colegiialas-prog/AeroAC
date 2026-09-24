package ac.grim.grimac.neural;

import ac.grim.grimac.neural.dataset.DatasetManager;
import ac.grim.grimac.neural.debug.NeuralMonitor;
import ac.grim.grimac.neural.debug.NeuralReport;
import ac.grim.grimac.neural.inference.*;
import ac.grim.grimac.neural.mitigation.MitigationAction;
import ac.grim.grimac.neural.mitigation.MitigationManager;
import ac.grim.grimac.neural.mitigation.PlayerMitigationState;
import ac.grim.grimac.neural.risk.*;
import ac.grim.grimac.neural.telemetry.CombatFrame;
import ac.grim.grimac.neural.telemetry.CombatTelemetryCollector;
import ac.grim.grimac.neural.telemetry.FrameField;
import ac.grim.grimac.player.GrimPlayer;

import java.util.function.Supplier;

/**
 * Everything a single configuration generation owns: the inference client, the risk engine, the
 * mitigation rules and the operator monitor. A reload builds a new runtime and closes the old one,
 * so nothing from a previous configuration can apply results into the new one.
 */
public final class NeuralRuntime implements AutoCloseable {
    private static final int EVIDENCE_HISTORY = 64;
    private static final int MITIGATION_HISTORY = 16;
    private static final int TRAIL_SIZE = 32;

    private final long generation;
    private final NeuralConfig config;
    private final InferenceClient client;
    private final InferenceGateway gateway;
    private final RiskEngine riskEngine;
    private final MitigationManager mitigation;
    private final NeuralMonitor monitor;
    private final Supplier<DatasetManager> datasets;

    public NeuralRuntime(long generation, NeuralConfig config, InferenceClient client, Supplier<DatasetManager> datasets) {
        this.generation = generation;
        this.config = config;
        this.datasets = datasets;
        this.client = client;
        this.gateway = client == null ? null : new InferenceGateway(config.inference(), client, this::onPrediction);
        this.riskEngine = config.risk().enabled() ? new RiskEngine(config.risk()) : null;
        this.mitigation = config.mitigation().enabled() ? new MitigationManager(config.mitigation()) : null;
        this.monitor = new NeuralMonitor(config.monitorIntervalMs());
    }

    public long generation() { return generation; }
    public NeuralConfig config() { return config; }
    public NeuralMonitor monitor() { return monitor; }
    public RiskEngine riskEngine() { return riskEngine; }
    public MitigationManager mitigation() { return mitigation; }
    public InferenceHealth health() { return client == null ? null : client.health(); }

    /** Outstanding inference requests, or -1 when inference is disabled or does not track them. */
    public int inFlight() { return client == null ? -1 : client.inFlight(); }

    /** Player event loop, immediately after a sample was added to the ring. */
    public void afterSample(GrimPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector, long nowNanos) {
        CombatFrame latest = collector.frames.size() == 0 ? null : collector.frames.get(collector.frames.size() - 1);
        if (state.pendingSnapshot != null && latest != null && state.pendingSnapshot.accept(latest)) {
            submitSnapshot(player, state);
        }
        if (riskEngine != null && state.risk != null) riskEngine.decay(state.risk, nowNanos);
        if (gateway != null) gateway.afterSample(player, state, collector, nowNanos);
        publishMonitor(player, state, collector, nowNanos);
    }

    /** Player event loop, from InferenceGateway. A result from an older collector is discarded. */
    private void onPrediction(GrimPlayer player, InferenceRequest request, PredictionResult result) {
        NeuralPlayerState state = player.getNeuralState();
        CombatTelemetryCollector collector = state.collector;
        // A reload replaces the runtime; an answer that was in flight across it belongs to the old one.
        if (ac.grim.grimac.GrimAPI.INSTANCE.getNeuralManager().runtime() != this) return;
        if (state.disconnected || collector == null || collector.generation() != request.collectorGeneration()) return;
        if (state.trail == null) state.trail = new PredictionTrail(TRAIL_SIZE);
        if (!state.trail.add(result)) return;
        if (riskEngine == null) return;
        if (state.risk == null) state.risk = new PlayerRiskProfile(EVIDENCE_HISTORY, result.nanoTime());
        Evidence evidence = riskEngine.fromPrediction(result, result.nanoTime());
        if (evidence == null) {
            riskEngine.decay(state.risk, result.nanoTime());
            return;
        }
        apply(player, state, collector, evidence, result);
    }

    /**
     * Player event loop, after an accepted Grim flag. A deterministic flag is one evidence family
     * here; it keeps its own violation accounting and punishments untouched.
     */
    public void onCheckFlag(GrimPlayer player, NeuralPlayerState state, String checkName, long nowNanos) {
        if (riskEngine == null) return;
        Evidence evidence = riskEngine.fromCheck(checkName, nowNanos);
        if (evidence == null) return;
        if (state.risk == null) state.risk = new PlayerRiskProfile(EVIDENCE_HISTORY, nowNanos);
        apply(player, state, state.collector, evidence, null);
    }

    private void apply(GrimPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector,
                       Evidence evidence, PredictionResult result) {
        double riskBefore = state.risk.risk();
        RiskState stateBefore = state.risk.state();
        riskEngine.accept(state.risk, evidence, evidence.nanoTime());
        double riskAfter = state.risk.risk();
        RiskState stateAfter = state.risk.state();
        if (crossedSnapshotThreshold(riskBefore, riskAfter) && collector != null) {
            beginSnapshot(player, state, collector, evidence, result, riskBefore, riskAfter, stateBefore, stateAfter);
        }
        // Evaluated on every accepted evidence, not only on a transition: a player who sits at a
        // high state would otherwise get one mitigation and nothing after it expired. The manager
        // itself refuses while one is running and enforces the hourly cap.
        if (mitigation != null && stateAfter.atLeast(config.mitigation().minState())) {
            if (state.mitigation == null) state.mitigation = new PlayerMitigationState(MITIGATION_HISTORY);
            MitigationAction action = mitigation.evaluate(state.mitigation, state.risk, evidence.nanoTime(),
                    evidence.type().name());
            if (action != null) {
                ac.grim.grimac.utils.anticheat.LogUtil.info("Aero mitigation started for " + player.getName()
                        + ": " + action.describe(evidence.nanoTime()));
            }
        }
    }

    private boolean crossedSnapshotThreshold(double before, double after) {
        return before < config.risk().snapshotThreshold() && after >= config.risk().snapshotThreshold();
    }

    private void beginSnapshot(GrimPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector,
                               Evidence evidence, PredictionResult result, double riskBefore, double riskAfter,
                               RiskState stateBefore, RiskState stateAfter) {
        if (state.pendingSnapshot != null || datasets.get() == null) return;
        long now = evidence.nanoTime();
        if (now - state.snapshotWindowStartNanos >= 3_600_000_000_000L) {
            state.snapshotWindowStartNanos = now;
            state.snapshotsInWindow = 0;
        }
        if (state.snapshotsInWindow >= config.risk().maxSnapshotsPerHour()) return;
        state.snapshotsInWindow++;
        long[] counts = new long[EvidenceType.values().length];
        for (EvidenceType type : EvidenceType.values()) counts[type.ordinal()] = state.risk.count(type);
        CombatFrame[] before = collector.recentFrames(config.risk().snapshotBefore());
        CombatFrame newest = before.length == 0 ? null : before[before.length - 1];
        state.pendingSnapshot = new PendingSnapshot(evidence, result, riskBefore, riskAfter, stateBefore, stateAfter,
                before, config.risk().snapshotAfter(), counts, newest == null ? now : newest.nanoTime(),
                newest == null ? Double.NaN : newest.value(FrameField.PING_MS),
                newest == null ? Double.NaN : newest.value(FrameField.SERVER_TICK_DURATION_MS),
                player.getClientVersion().getProtocolVersion());
        if (state.pendingSnapshot.complete()) submitSnapshot(player, state);
    }

    private void submitSnapshot(GrimPlayer player, NeuralPlayerState state) {
        PendingSnapshot pending = state.pendingSnapshot;
        state.pendingSnapshot = null;
        DatasetManager manager = datasets.get();
        if (pending == null || manager == null) return;
        try {
            manager.submitSnapshot(pending.build(manager.pseudonym(player.getUniqueId())));
        } catch (RuntimeException error) {
            ac.grim.grimac.utils.anticheat.LogUtil.warn("Aero evidence snapshot skipped: " + error.getMessage());
        }
    }

    private void publishMonitor(GrimPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector, long nowNanos) {
        if (!state.monitored) return;
        if (monitor.publish(player.getUniqueId(), nowNanos, state.lastMonitorNanos,
                () -> NeuralReport.monitorLine(player, state, collector, this, nowNanos))) {
            state.lastMonitorNanos = nowNanos;
        }
    }

    /** Reported state folds an active mitigation in; the stored risk state itself is untouched. */
    public RiskState reportedState(NeuralPlayerState state, long nowNanos) {
        RiskState base = state.risk == null ? RiskState.CLEAN : state.risk.state();
        return mitigation == null ? base : mitigation.report(state.mitigation, base, nowNanos);
    }

    public boolean shouldCancelAttack(NeuralPlayerState state, long nowNanos) {
        return mitigation != null && mitigation.shouldCancelAttacks(state.mitigation, nowNanos);
    }

    @Override public void close() {
        if (client != null) client.close();
    }
}
