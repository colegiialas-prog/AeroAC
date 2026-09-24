package ac.grim.grimac.neural.risk;

import ac.grim.grimac.neural.inference.PredictionResult;
import ac.grim.grimac.neural.telemetry.CombatFrame;

import java.util.UUID;

/**
 * Detached record of one strong event, kept so a false positive can be argued about afterwards.
 * Holds immutable frames and a pseudonymous id only: no GrimPlayer, no username, no UUID.
 */
public record EvidenceSnapshot(UUID eventId, String playerId, long timestampMillis, long anchorNanos,
                               double riskBefore, double riskAfter, RiskState stateBefore, RiskState stateAfter,
                               Evidence trigger, PredictionResult prediction, CombatFrame[] framesBefore,
                               CombatFrame[] framesAfter, long[] evidenceCounts, double pingMs,
                               double serverTickDurationMs, int minecraftProtocol) {

    public EvidenceSnapshot {
        if (eventId == null || playerId == null || trigger == null) {
            throw new IllegalArgumentException("Incomplete snapshot identity");
        }
        if (framesBefore == null || framesAfter == null) throw new IllegalArgumentException("Missing frames");
        if (evidenceCounts == null || evidenceCounts.length != EvidenceType.values().length) {
            throw new IllegalArgumentException("Evidence counters do not match the evidence types");
        }
    }

    public int frameCount() { return framesBefore.length + framesAfter.length; }
}
