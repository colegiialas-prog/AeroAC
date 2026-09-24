package dev.aeroac.neural.admin;

import dev.aeroac.neural.risk.RiskState;

import java.util.UUID;

/**
 * One immutable row of the administrator interface.
 *
 * <p>Built on the owning player's event loop and then published; every reader works from a finished
 * object and never touches live neural state. Nothing here is recomputed: each field is a value the
 * risk engine, the prediction trail or the telemetry collector already held.
 *
 * <p>A missing value is {@link Double#NaN} and a missing count is negative, never zero. The
 * difference between "the model said 0%" and "there is no model output" is the whole point of this
 * screen, so it is preserved all the way to the item lore.
 */
public record AdminPlayerView(UUID uuid, String name, RiskState state, double risk, double peakRisk,
                              long transitions, long stateForSeconds, boolean neuralEnabled,
                              double overall, double aimAssist, double killAura, double triggerBot,
                              DominantSignal dominant, String model, String modelVersion, boolean calibrated,
                              long predictionAgeMs, long predictionLatencyMs, int predictionCount,
                              int ping, long combatSeconds, int evidenceCount, boolean telemetryActive,
                              int targetEntityId, double aimError, double deltaYaw, double deltaPitch,
                              String mitigation, long mitigationRemainingMs, int mitigationCount,
                              long attacksSuppressed, boolean watched, RecordingView recording,
                              long[] evidenceByType) {

    public AdminPlayerView {
        evidenceByType = evidenceByType == null ? new long[0] : evidenceByType.clone();
    }

    @Override public long[] evidenceByType() { return evidenceByType.clone(); }

    /** A view for a player the neural runtime has never seen. Renders as NO DATA everywhere. */
    public static AdminPlayerView empty(UUID uuid, String name, int ping) {
        return new AdminPlayerView(uuid, name, RiskState.CLEAN, 0, 0, 0, 0, false,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, null, null, false,
                -1, -1, 0, ping, -1, 0, false, -1, Double.NaN, Double.NaN, Double.NaN,
                null, 0, 0, 0, false, null, new long[dev.aeroac.neural.risk.EvidenceType.values().length]);
    }

    public boolean hasPrediction() { return predictionCount > 0 && Double.isFinite(overall); }

    public boolean suspicious() { return state != null && state.atLeast(RiskState.WATCH); }

    public boolean isRecording() { return recording != null; }

    /** Accepted evidence of one family since this connection began. Zero is a real zero here. */
    public long evidenceOf(dev.aeroac.neural.risk.EvidenceType type) {
        return evidenceByType == null || type.ordinal() >= evidenceByType.length ? 0
                : evidenceByType[type.ordinal()];
    }

    /** The accumulated risk is the primary list order, independently of model availability. */
    public double sortKey() {
        return Double.isFinite(risk) ? risk : -1;
    }
}
