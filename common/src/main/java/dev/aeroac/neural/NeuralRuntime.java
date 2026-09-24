package dev.aeroac.neural;

import dev.aeroac.neural.dataset.DatasetManager;
import dev.aeroac.neural.debug.NeuralMonitor;
import dev.aeroac.neural.debug.NeuralReport;
import dev.aeroac.neural.inference.*;
import dev.aeroac.neural.mitigation.MitigationAction;
import dev.aeroac.neural.mitigation.MitigationManager;
import dev.aeroac.neural.mitigation.PlayerMitigationState;
import dev.aeroac.neural.risk.*;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.CombatTelemetryCollector;
import dev.aeroac.neural.telemetry.FrameField;
import dev.aeroac.player.AeroPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Upper bound on remembered profiles of players who just left; beyond it nothing more is kept. */
    private static final int MAX_PARKED = 4096;

    private final long generation;
    private final NeuralConfig config;
    private final InferenceClient client;
    private final InferenceGateway gateway;
    private final RiskEngine riskEngine;
    private final MitigationManager mitigation;
    private final NeuralMonitor monitor;
    private final Supplier<DatasetManager> datasets;
    /**
     * Risk of players who disconnected less than {@code risk.carry-over-seconds} ago. Without it a
     * relog wiped a SUSPICIOUS player back to CLEAN in two seconds, which made "leave before it adds
     * up" the cheapest way around the whole engine. The profile keeps its own evidence and its last
     * update time, so the offline minutes decay it exactly as if the player had stayed.
     */
    private final ConcurrentHashMap<UUID, Parked> parked = new ConcurrentHashMap<>();

    private record Parked(PlayerRiskProfile profile, long nanoTime) { }

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
    public void afterSample(AeroPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector, long nowNanos) {
        CombatFrame latest = collector.frames.size() == 0 ? null : collector.frames.get(collector.frames.size() - 1);
        if (state.pendingSnapshot != null && latest != null && state.pendingSnapshot.accept(latest)) {
            submitSnapshot(player, state);
        }
        if (riskEngine != null && state.risk != null) riskEngine.decay(state.risk, nowNanos);
        if (gateway != null) gateway.afterSample(player, state, collector, nowNanos);
        publishMonitor(player, state, collector, nowNanos);
    }

    /** Player event loop, from InferenceGateway. A result from an older collector is discarded. */
    private void onPrediction(AeroPlayer player, InferenceRequest request, PredictionResult result) {
        NeuralPlayerState state = player.getNeuralState();
        CombatTelemetryCollector collector = state.collector;
        // A reload replaces the runtime; an answer that was in flight across it belongs to the old one.
        if (dev.aeroac.AeroAPI.INSTANCE.getNeuralManager().runtime() != this) return;
        if (state.disconnected || collector == null || collector.generation() != request.collectorGeneration()) return;
        if (state.trail == null) state.trail = new PredictionTrail(TRAIL_SIZE);
        if (!state.trail.add(result)) return;
        if (riskEngine == null) return;
        PlayerRiskProfile profile = riskProfile(player.getUniqueId(), state, result.nanoTime());
        Evidence evidence = riskEngine.fromPrediction(result, result.nanoTime());
        if (evidence == null) {
            riskEngine.decay(profile, result.nanoTime());
            return;
        }
        apply(player, state, collector, evidence, result);
    }

    /**
     * Player event loop, after an accepted Grim flag. A deterministic flag is one evidence family
     * here; it keeps its own violation accounting and punishments untouched.
     */
    public void onCheckFlag(AeroPlayer player, NeuralPlayerState state, String checkName, long nowNanos) {
        if (riskEngine == null) return;
        Evidence evidence = riskEngine.fromCheck(checkName, nowNanos);
        if (evidence == null) return;
        riskProfile(player.getUniqueId(), state, nowNanos);
        apply(player, state, state.collector, evidence, null);
    }

    /** The player's profile, restored from a recent disconnect when there is one. Player event loop. */
    PlayerRiskProfile riskProfile(UUID player, NeuralPlayerState state, long nowNanos) {
        if (state.risk != null) return state.risk;
        Parked back = player == null ? null : parked.remove(player);
        state.risk = back != null && nowNanos - back.nanoTime() <= carryOverNanos()
                ? back.profile()
                : new PlayerRiskProfile(EVIDENCE_HISTORY, nowNanos);
        return state.risk;
    }

    /**
     * Keeps a disconnecting player's profile for {@code risk.carry-over-seconds}. Called once, after
     * the player's event loop has stopped applying anything to it (the state is marked disconnected).
     */
    public void park(UUID player, PlayerRiskProfile profile, long nowNanos) {
        long ttl = carryOverNanos();
        if (player == null || profile == null || ttl <= 0 || riskEngine == null || !(profile.risk() > 0)) return;
        parked.values().removeIf(entry -> nowNanos - entry.nanoTime() > ttl);
        if (parked.size() >= MAX_PARKED && !parked.containsKey(player)) return;
        parked.put(player, new Parked(profile, nowNanos));
    }

    /** Players whose risk is currently being remembered across a disconnect. */
    public int parkedCount() { return parked.size(); }

    private long carryOverNanos() { return config.risk().carryOverSeconds() * 1_000_000_000L; }

    private void apply(AeroPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector,
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
                dev.aeroac.utils.anticheat.LogUtil.info("Aero mitigation started for " + player.getName()
                        + ": " + action.describe(evidence.nanoTime()));
            }
        }
    }

    private boolean crossedSnapshotThreshold(double before, double after) {
        return before < config.risk().snapshotThreshold() && after >= config.risk().snapshotThreshold();
    }

    private void beginSnapshot(AeroPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector,
                               Evidence evidence, PredictionResult result, double riskBefore, double riskAfter,
                               RiskState stateBefore, RiskState stateAfter) {
        if (state.pendingSnapshot != null || datasets.get() == null) return;
        long now = evidence.nanoTime();
        // nanoTime has an arbitrary origin, so the first window opens at the first snapshot, not at 0.
        if (!state.snapshotWindowOpen || now - state.snapshotWindowStartNanos >= 3_600_000_000_000L) {
            state.snapshotWindowStartNanos = now;
            state.snapshotsInWindow = 0;
            state.snapshotWindowOpen = true;
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

    private void submitSnapshot(AeroPlayer player, NeuralPlayerState state) {
        PendingSnapshot pending = state.pendingSnapshot;
        state.pendingSnapshot = null;
        DatasetManager manager = datasets.get();
        if (pending == null || manager == null) return;
        try {
            manager.submitSnapshot(pending.build(manager.pseudonym(player.getUniqueId())));
        } catch (RuntimeException error) {
            dev.aeroac.utils.anticheat.LogUtil.warn("Aero evidence snapshot skipped: " + error.getMessage());
        }
    }

    private void publishMonitor(AeroPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector, long nowNanos) {
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
