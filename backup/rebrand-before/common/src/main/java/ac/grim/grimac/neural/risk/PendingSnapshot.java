package ac.grim.grimac.neural.risk;

import ac.grim.grimac.neural.inference.PredictionResult;
import ac.grim.grimac.neural.telemetry.CombatFrame;

import java.util.UUID;

/**
 * A snapshot cannot be finished at the moment it triggers: the frames after the event have not
 * happened yet. This holds the already-captured history and fills the tail sample by sample.
 * Event-loop confined.
 */
public final class PendingSnapshot {
    private final UUID eventId = UUID.randomUUID();
    private final Evidence trigger;
    private final PredictionResult prediction;
    private final double riskBefore;
    private final double riskAfter;
    private final RiskState stateBefore;
    private final RiskState stateAfter;
    private final CombatFrame[] before;
    private final CombatFrame[] after;
    private final long[] counts;
    private final long anchorNanos;
    private final long timestampMillis;
    private final double pingMs;
    private final double serverTickDurationMs;
    private final int minecraftProtocol;
    private int filled;
    private boolean truncated;

    public PendingSnapshot(Evidence trigger, PredictionResult prediction, double riskBefore, double riskAfter,
                           RiskState stateBefore, RiskState stateAfter, CombatFrame[] before, int afterCount,
                           long[] counts, long anchorNanos, double pingMs, double serverTickDurationMs,
                           int minecraftProtocol) {
        if (trigger == null || before == null || counts == null) throw new IllegalArgumentException("Incomplete snapshot");
        if (afterCount < 0 || afterCount > 512) throw new IllegalArgumentException("afterCount must be 0..512");
        this.trigger = trigger;
        this.prediction = prediction;
        this.riskBefore = riskBefore;
        this.riskAfter = riskAfter;
        this.stateBefore = stateBefore;
        this.stateAfter = stateAfter;
        this.before = before;
        this.after = new CombatFrame[afterCount];
        this.counts = counts.clone();
        this.anchorNanos = anchorNanos;
        this.timestampMillis = System.currentTimeMillis();
        this.pingMs = pingMs;
        this.serverTickDurationMs = serverTickDurationMs;
        this.minecraftProtocol = minecraftProtocol;
    }

    /**
     * Returns true once the tail is finished. A segment boundary ends it early rather than
     * stitching samples from after a teleport or respawn onto the event being reviewed.
     */
    public boolean accept(CombatFrame frame) {
        if (filled > 0 && frame.value(ac.grim.grimac.neural.telemetry.FrameField.SEGMENT_START) == 1) {
            truncated = true;
            return true;
        }
        if (filled < after.length) after[filled++] = frame;
        return filled >= after.length;
    }

    public boolean complete() { return truncated || filled >= after.length; }

    public EvidenceSnapshot build(String playerId) {
        CombatFrame[] tail = filled == after.length ? after : java.util.Arrays.copyOf(after, filled);
        return new EvidenceSnapshot(eventId, playerId, timestampMillis, anchorNanos, riskBefore, riskAfter,
                stateBefore, stateAfter, trigger, prediction, before, tail, counts, pingMs, serverTickDurationMs,
                minecraftProtocol);
    }
}
