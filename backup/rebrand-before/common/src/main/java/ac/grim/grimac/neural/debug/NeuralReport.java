package ac.grim.grimac.neural.debug;

import ac.grim.grimac.neural.NeuralPlayerState;
import ac.grim.grimac.neural.NeuralRuntime;
import ac.grim.grimac.neural.inference.PredictionResult;
import ac.grim.grimac.neural.inference.PredictionTrail;
import ac.grim.grimac.neural.mitigation.MitigationAction;
import ac.grim.grimac.neural.risk.Evidence;
import ac.grim.grimac.neural.risk.EvidenceType;
import ac.grim.grimac.neural.risk.PlayerRiskProfile;
import ac.grim.grimac.neural.telemetry.CombatFrame;
import ac.grim.grimac.neural.telemetry.CombatTelemetryCollector;
import ac.grim.grimac.neural.telemetry.FrameField;
import ac.grim.grimac.player.GrimPlayer;

import java.util.function.Consumer;

/**
 * Operator-facing rendering. No claim here is an explanation of the network: it shows the evidence
 * that moved the risk, the raw geometry behind it and what the model actually returned.
 *
 * <p>Player names appear only in output for humans. They never enter telemetry or model features.
 */
public final class NeuralReport {
    private NeuralReport() { }

    /** Called at most once per monitor interval, on the player event loop. */
    public static String monitorLine(GrimPlayer player, NeuralPlayerState state, CombatTelemetryCollector collector,
                                     NeuralRuntime runtime, long nowNanos) {
        StringBuilder line = new StringBuilder("[aero] ").append(player.getName());
        PlayerRiskProfile profile = state.risk;
        line.append(" state=").append(runtime.reportedState(state, nowNanos));
        line.append(" risk=").append(profile == null ? "0.00" : String.format("%.2f", profile.risk()));
        PredictionResult latest = state.trail == null ? null : state.trail.latest();
        if (latest == null) {
            line.append(" model=none");
        } else {
            line.append(" overall=").append(percent(latest.overall()))
                    .append(" aim=").append(percent(latest.head("aimAssist")))
                    .append(" age=").append((nowNanos - latest.nanoTime()) / 1_000_000L).append("ms");
        }
        CombatFrame frame = newest(collector);
        if (frame != null) {
            boolean target = frame.value(FrameField.TARGET_PRESENT) == 1;
            line.append(" target=").append(target ? (int) frame.value(FrameField.TARGET_ENTITY_ID) : "none");
            line.append(" aimError=").append(number(frame.value(FrameField.AIM_ERROR_TOTAL)));
            line.append(" d=").append(number(frame.value(FrameField.DELTA_YAW)))
                    .append('/').append(number(frame.value(FrameField.DELTA_PITCH)));
            line.append(" ping=").append(number(frame.value(FrameField.PING_MS))).append("ms");
        }
        MitigationAction action = state.mitigation == null ? null : state.mitigation.current(nowNanos);
        line.append(" mitigation=").append(action == null ? "none"
                : action.rule() + "(" + action.remainingMs(nowNanos) + "ms)");
        return line.toString();
    }

    /** Multi-line profile for /neural profile. Each line is delivered separately by the caller. */
    public static void profile(GrimPlayer player, NeuralPlayerState state, NeuralRuntime runtime,
                               long nowNanos, Consumer<String> out) {
        out.accept("Player: " + player.getName() + "  ping=" + player.getTransactionPing() + "ms  protocol="
                + player.getClientVersion().getProtocolVersion());
        PlayerRiskProfile profile = state.risk;
        if (runtime == null) {
            out.accept("Neural runtime disabled; deterministic checks unaffected.");
            return;
        }
        out.accept("State: " + runtime.reportedState(state, nowNanos)
                + "  Risk: " + (profile == null ? "0.00" : String.format("%.2f", profile.risk()))
                + (profile == null ? "" : "  peak=" + String.format("%.2f", profile.peakRisk())
                + "  transitions=" + profile.transitions()
                + "  inState=" + (nowNanos - profile.stateSinceNanos()) / 1_000_000_000L + "s"));
        predictions(state.trail, nowNanos, out);
        evidence(profile, out);
        mitigation(state, nowNanos, out);
        telemetry(state.collector, out);
    }

    private static void predictions(PredictionTrail trail, long nowNanos, Consumer<String> out) {
        if (trail == null || trail.size() == 0) {
            out.accept("Predictions: none (inference disabled, offline or no complete window yet)");
            return;
        }
        PredictionResult latest = trail.latest();
        out.accept("Latest prediction: " + latest.describe()
                + "  age=" + (nowNanos - latest.nanoTime()) / 1_000_000L + "ms");
        StringBuilder recent = new StringBuilder("Recent overall:");
        for (int i = Math.max(0, trail.size() - 8); i < trail.size(); i++) {
            recent.append(' ').append(String.format("%.2f", trail.get(i).overall()));
        }
        out.accept(recent.append("  accepted=").append(trail.accepted())
                .append(" staleDropped=").append(trail.rejectedStale()).toString());
    }

    private static void evidence(PlayerRiskProfile profile, Consumer<String> out) {
        if (profile == null || profile.evidenceSize() == 0) {
            out.accept("Evidence: none");
            return;
        }
        StringBuilder counts = new StringBuilder("Evidence:");
        for (EvidenceType type : EvidenceType.values()) {
            long count = profile.count(type);
            if (count > 0) counts.append(' ').append(type).append(" x").append(count);
        }
        out.accept(counts.toString());
        StringBuilder last = new StringBuilder("Most recent:");
        for (int i = Math.max(0, profile.evidenceSize() - 3); i < profile.evidenceSize(); i++) {
            Evidence item = profile.evidence(i);
            last.append(' ').append(item.describe());
        }
        out.accept(last.toString());
    }

    private static void mitigation(NeuralPlayerState state, long nowNanos, Consumer<String> out) {
        if (state.mitigation == null || state.mitigation.size() == 0) {
            out.accept("Mitigation: none");
            return;
        }
        MitigationAction current = state.mitigation.current(nowNanos);
        out.accept("Mitigation: " + (current == null ? "inactive" : current.describe(nowNanos))
                + "  history=" + state.mitigation.size() + "  attacksDropped=" + state.mitigation.suppressed());
    }

    private static void telemetry(CombatTelemetryCollector collector, Consumer<String> out) {
        if (collector == null) {
            out.accept("Telemetry: not collecting (no combat yet, or neural telemetry disabled)");
            return;
        }
        CombatFrame frame = newest(collector);
        if (frame == null) {
            out.accept("Telemetry: collector active, no samples yet");
            return;
        }
        out.accept("Telemetry: tick=" + frame.tick() + " ring=" + collector.frames.size() + "/" + collector.frames.capacity()
                + " target=" + (frame.value(FrameField.TARGET_PRESENT) == 1
                ? (int) frame.value(FrameField.TARGET_ENTITY_ID) : "none")
                + " aimError=" + number(frame.value(FrameField.AIM_ERROR_TOTAL))
                + " deltaYaw/Pitch=" + number(frame.value(FrameField.DELTA_YAW))
                + "/" + number(frame.value(FrameField.DELTA_PITCH))
                + " distance=" + number(frame.value(FrameField.DISTANCE_TO_TARGET)));
        CombatFrame[] window = collector.latestAttackWindow();
        out.accept("Complete attack window: " + (window == null ? "waiting for history" : window.length + " samples"));
    }

    private static CombatFrame newest(CombatTelemetryCollector collector) {
        if (collector == null || collector.frames.size() == 0) return null;
        return collector.frames.get(collector.frames.size() - 1);
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "n/a" : String.format("%.0f%%", value * 100);
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "n/a" : String.format("%.2f", value);
    }
}
