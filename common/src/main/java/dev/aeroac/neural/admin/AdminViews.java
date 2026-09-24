package dev.aeroac.neural.admin;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.NeuralPlayerState;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.neural.dataset.DatasetSession;
import dev.aeroac.neural.inference.ModelKind;
import dev.aeroac.neural.inference.PredictionResult;
import dev.aeroac.neural.inference.PredictionTrail;
import dev.aeroac.neural.mitigation.MitigationAction;
import dev.aeroac.neural.mitigation.PlayerMitigationState;
import dev.aeroac.neural.risk.Evidence;
import dev.aeroac.neural.risk.EvidenceType;
import dev.aeroac.neural.risk.PlayerRiskProfile;
import dev.aeroac.neural.risk.RiskState;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.CombatTelemetryCollector;
import dev.aeroac.neural.telemetry.FrameField;
import dev.aeroac.player.AeroPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns live neural state into immutable views.
 *
 * <p>Every method here must run on the owning player's event loop, because that is where the
 * prediction trail, the risk profile and the collector are confined. The result is a finished
 * object that any thread may read, which is what keeps the interface off the hot path: nothing
 * downstream ever has to reach back into the engine, take a lock or recompute a value.
 *
 * <p>Nothing is derived that the engine did not already compute. Where a value is genuinely absent
 * it stays {@code NaN} or negative so the renderer can say NO DATA instead of inventing a zero.
 */
public final class AdminViews {
    /** Panes on the profile timeline. Bounded by the prediction trail, which is bounded already. */
    public static final int TIMELINE_LENGTH = 9;
    /** Predictions, evidence and mitigations listed on a history screen. */
    public static final int HISTORY_LENGTH = 28;
    /** Frames sampled for the live recording quality indicators. */
    private static final int QUALITY_SAMPLE = 128;

    private AdminViews() { }

    /** Player event loop. Cheap enough to run for every online player on the refresh interval. */
    public static AdminPlayerView summary(AeroPlayer player, NeuralRuntime runtime, long nowNanos, boolean watched) {
        NeuralPlayerState state = player.getNeuralState();
        int ping = ping(player);
        if (runtime == null || state.disconnected || (state.collector != null
                && state.collector.generation() != runtime.generation())) {
            return AdminPlayerView.empty(player.getUniqueId(), player.getName(), ping);
        }

        PlayerRiskProfile profile = state.risk;
        RiskState reported = runtime.reportedState(state, nowNanos);
        PredictionResult latest = state.trail == null ? null : state.trail.latest();
        CombatTelemetryCollector collector = state.collector;
        CombatFrame frame = newest(collector);
        MitigationAction mitigation = state.mitigation == null ? null : state.mitigation.current(nowNanos);

        boolean targetPresent = frame != null && frame.value(FrameField.TARGET_PRESENT) == 1;
        return new AdminPlayerView(player.getUniqueId(), player.getName(), reported,
                profile == null ? 0 : profile.risk(),
                profile == null ? 0 : profile.peakRisk(),
                profile == null ? 0 : profile.transitions(),
                profile == null ? 0 : (nowNanos - profile.stateSinceNanos()) / 1_000_000_000L,
                true,
                latest == null ? Double.NaN : latest.overall(),
                latest == null ? Double.NaN : latest.head("aimAssist"),
                latest == null ? Double.NaN : latest.head("killAura"),
                latest == null ? Double.NaN : latest.head("triggerBot"),
                DominantSignal.of(latest),
                latest == null ? null : latest.model().wireName(),
                latest == null ? null : latest.modelVersion(),
                latest != null && latest.calibrated(),
                latest == null ? -1 : (nowNanos - latest.nanoTime()) / 1_000_000L,
                latest == null ? -1 : latest.latencyMs(),
                state.trail == null ? 0 : state.trail.size(),
                ping,
                collector == null ? -1 : collector.combatSeconds(),
                profile == null ? 0 : profile.evidenceSize(),
                collector != null,
                targetPresent ? (int) frame.value(FrameField.TARGET_ENTITY_ID) : -1,
                frame == null ? Double.NaN : frame.value(FrameField.AIM_ERROR_TOTAL),
                frame == null ? Double.NaN : frame.value(FrameField.DELTA_YAW),
                frame == null ? Double.NaN : frame.value(FrameField.DELTA_PITCH),
                mitigation == null ? null : mitigation.rule().name(),
                mitigation == null ? 0 : mitigation.remainingMs(nowNanos),
                state.mitigation == null ? 0 : state.mitigation.size(),
                state.mitigation == null ? 0 : state.mitigation.suppressed(),
                watched,
                recording(player, runtime, nowNanos),
                evidenceCounts(profile));
    }

    /** One counter per evidence family, so a screen can total them without walking the ring. */
    private static long[] evidenceCounts(PlayerRiskProfile profile) {
        long[] counts = new long[EvidenceType.values().length];
        if (profile != null) {
            for (EvidenceType type : EvidenceType.values()) counts[type.ordinal()] = profile.count(type);
        }
        return counts;
    }

    /** Player event loop, on demand only: this walks three ring buffers. */
    public static AdminDetailView detail(AeroPlayer player, NeuralRuntime runtime, long nowNanos, boolean watched) {
        AdminPlayerView summary = summary(player, runtime, nowNanos, watched);
        if (runtime == null || !summary.neuralEnabled()) return AdminDetailView.empty(summary);
        NeuralPlayerState state = player.getNeuralState();
        PlayerRiskProfile profile = state.risk;

        long[] counts = evidenceCounts(profile);
        return new AdminDetailView(summary, predictions(state.trail, runtime, nowNanos), evidence(profile, nowNanos),
                mitigations(state.mitigation, nowNanos), counts, timeline(state.trail),
                state.trail == null ? 0 : state.trail.accepted(),
                state.trail == null ? 0 : state.trail.rejectedStale());
    }

    private static List<AdminDetailView.Prediction> predictions(PredictionTrail trail, NeuralRuntime runtime, long nowNanos) {
        if (trail == null || trail.size() == 0) return List.of();
        List<AdminDetailView.Prediction> rows = new ArrayList<>();
        for (int i = Math.max(0, trail.size() - HISTORY_LENGTH); i < trail.size(); i++) {
            PredictionResult result = trail.get(i);
            rows.add(new AdminDetailView.Prediction(result.model().wireName(), result.modelVersion(),
                    result.calibrated(), windowOf(result.model(), runtime), result.overall(),
                    result.headNames().clone(), result.headValues().clone(), result.latencyMs(),
                    (nowNanos - result.nanoTime()) / 1_000_000L));
        }
        return List.copyOf(rows);
    }

    /** The window a model reads is a configuration fact, not something the response carries. */
    private static String windowOf(ModelKind kind, NeuralRuntime runtime) {
        if (runtime == null) return AeroMessages.tr("admin.unknown");
        return kind == ModelKind.PRO
                ? runtime.config().inference().proWindow().wireName()
                : runtime.config().inference().flashWindow().wireName();
    }

    private static List<AdminDetailView.Evidence> evidence(PlayerRiskProfile profile, long nowNanos) {
        if (profile == null || profile.evidenceSize() == 0) return List.of();
        List<AdminDetailView.Evidence> rows = new ArrayList<>();
        for (int i = Math.max(0, profile.evidenceSize() - HISTORY_LENGTH); i < profile.evidenceSize(); i++) {
            Evidence item = profile.evidence(i);
            rows.add(new AdminDetailView.Evidence(item.type(), item.strength(), item.source(), item.metadata(),
                    (nowNanos - item.nanoTime()) / 1_000_000L));
        }
        return List.copyOf(rows);
    }

    private static List<AdminDetailView.Mitigation> mitigations(PlayerMitigationState state, long nowNanos) {
        if (state == null || state.size() == 0) return List.of();
        List<AdminDetailView.Mitigation> rows = new ArrayList<>();
        for (int i = Math.max(0, state.size() - HISTORY_LENGTH); i < state.size(); i++) {
            MitigationAction action = state.get(i);
            rows.add(new AdminDetailView.Mitigation(action.rule().name(), action.riskAtTrigger(),
                    action.stateAtTrigger(), action.reason(), action.durationMs(), action.remainingMs(nowNanos),
                    (nowNanos - action.startNanos()) / 1_000_000L));
        }
        return List.copyOf(rows);
    }

    /** Last overall values, oldest first. NaN marks a prediction that published no overall head. */
    private static double[] timeline(PredictionTrail trail) {
        if (trail == null || trail.size() == 0) return new double[0];
        int length = Math.min(TIMELINE_LENGTH, trail.size());
        double[] values = new double[length];
        for (int i = 0; i < length; i++) values[i] = trail.get(trail.size() - length + i).overall();
        return values;
    }

    /** Player event loop. Returns null when this player is not being recorded. */
    public static RecordingView recording(AeroPlayer player, NeuralRuntime runtime, long nowNanos) {
        NeuralPlayerState state = player.getNeuralState();
        DatasetSession session = state.session;
        if (session == null || session.completed()) return null;
        CombatTelemetryCollector collector = state.collector;
        DatasetMetadata metadata = session.metadata;
        double[] quality = quality(collector, metadata.startNanos());
        return new RecordingView(metadata.sessionId(), player.getName(), player.getUniqueId(),
                RecordingView.stateOf(session), metadata.label(), metadata.cheatFamily(), metadata.clientFamily(),
                metadata.configuration(), metadata.assistStrength(), metadata.scenario(),
                Math.max(0, nowNanos - metadata.startNanos()) / 1_000_000_000L,
                session.framesWritten(), session.recordsWritten(),
                collector == null ? 0 : collector.lifetimeAttacks(),
                collector == null ? 0 : collector.lifetimeSwings(),
                collector == null ? 0 : collector.lifetimeAttackWindows(),
                session.dropped(), session.queued(), session.closeReason(), session.failure(),
                quality[0], quality[1], quality[2], collector == null ? 0 : collector.recordingMovementGaps());
    }

    /**
     * Fraction of recent frames that actually carry a target, an aim error and target geometry.
     *
     * <p>This is the cheapest honest signal available at runtime: it reads the ring the collector
     * already fills, over a bounded tail, once per refresh. It says how much of the recording has
     * usable combat context in it, and nothing more. It is not an audit and never a verdict.
     */
    private static double[] quality(CombatTelemetryCollector collector, long startNanos) {
        if (collector == null || collector.frames.size() == 0) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        int size = collector.frames.size();
        int from = Math.max(0, size - QUALITY_SAMPLE);
        int counted = 0, target = 0, aim = 0, geometry = 0;
        for (int i = from; i < size; i++) {
            CombatFrame frame = collector.frames.get(i);
            if (frame.nanoTime() < startNanos) continue;
            counted++;
            if (frame.value(FrameField.TARGET_PRESENT) == 1) target++;
            if (Double.isFinite(frame.value(FrameField.AIM_ERROR_TOTAL))) aim++;
            if (Double.isFinite(frame.value(FrameField.TARGET_MIN_X))
                    && Double.isFinite(frame.value(FrameField.TARGET_MIN_Y))
                    && Double.isFinite(frame.value(FrameField.TARGET_MIN_Z))
                    && Double.isFinite(frame.value(FrameField.TARGET_MAX_X))
                    && Double.isFinite(frame.value(FrameField.TARGET_MAX_Y))
                    && Double.isFinite(frame.value(FrameField.TARGET_MAX_Z))) geometry++;
        }
        if (counted == 0) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        return new double[]{target / (double) counted, aim / (double) counted, geometry / (double) counted};
    }

    private static CombatFrame newest(CombatTelemetryCollector collector) {
        if (collector == null || collector.frames.size() == 0) return null;
        return collector.frames.get(collector.frames.size() - 1);
    }

    private static int ping(AeroPlayer player) {
        try {
            return player.getTransactionPing();
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }
}
