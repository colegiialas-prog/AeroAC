package ac.grim.grimac.neural.risk;

import ac.grim.grimac.neural.NeuralConfig;
import ac.grim.grimac.neural.inference.PredictionResult;

/**
 * Accumulates evidence over time instead of reacting to any single prediction. Pure logic: it holds
 * no player, no clock and no I/O, so every transition here is unit testable.
 *
 * <p>risk(t) = risk(t-1) * exp(-decay * elapsedSeconds) + strength, clamped into [0, maxRisk].
 * A lone spike therefore cannot cross a state boundary the operator set above one evidence unit.
 */
public final class RiskEngine {
    private final NeuralConfig.Risk config;

    public RiskEngine(NeuralConfig.Risk config) {
        this.config = config;
    }

    public NeuralConfig.Risk config() { return config; }

    /** Time-only update. Safe to call at any rate; it is idempotent for a repeated timestamp. */
    public void decay(PlayerRiskProfile profile, long nowNanos) {
        double elapsed = (nowNanos - profile.lastUpdateNanos()) / 1_000_000_000.0;
        if (!(elapsed > 0)) return;
        double decayed = profile.risk() * Math.exp(-config.decayPerSecond() * elapsed);
        profile.risk(clamp(decayed), nowNanos);
        profile.state(stateFor(profile.risk()), nowNanos);
    }

    /** Applies decay up to now, then the evidence. Returns true when the state boundary moved. */
    public boolean accept(PlayerRiskProfile profile, Evidence evidence, long nowNanos) {
        decay(profile, nowNanos);
        RiskState before = profile.state();
        profile.risk(clamp(profile.risk() + evidence.strength()), nowNanos);
        profile.record(evidence);
        profile.state(stateFor(profile.risk()), nowNanos);
        return profile.state() != before;
    }

    public RiskState stateFor(double risk) {
        if (risk >= config.confirmed()) return RiskState.CONFIRMED;
        if (risk >= config.suspicious()) return RiskState.SUSPICIOUS;
        if (risk >= config.watch()) return RiskState.WATCH;
        return RiskState.CLEAN;
    }

    /**
     * Maps one prediction onto at most one evidence item, preferring the most specific head that
     * crossed the threshold. Returns null in the ordinary case where the model saw nothing notable.
     */
    public Evidence fromPrediction(PredictionResult result, long nowNanos) {
        if (result == null) return null;
        if (!result.calibrated() && !config.acceptUncalibrated()) return null;
        String source = result.model().wireName() + "/" + result.modelVersion();
        for (String head : new String[]{"aimAssist", "killAura", "triggerBot", "overall"}) {
            double probability = result.head(head);
            if (Double.isNaN(probability) || probability < config.aiThreshold()) continue;
            return new Evidence(typeFor(head), config.aiWeight() * escalation(probability), nowNanos, source,
                    head + "=" + String.format("%.3f", probability));
        }
        double overall = result.overall();
        if (!Double.isNaN(overall) && overall <= config.aiClearThreshold() && config.aiClearThreshold() > 0) {
            double relief = config.aiRelief() * (config.aiClearThreshold() - overall) / config.aiClearThreshold();
            if (relief <= 0) return null;
            return new Evidence(EvidenceType.AI_RELIEF, -relief, nowNanos, source,
                    "overall=" + String.format("%.3f", overall));
        }
        return null;
    }

    /** A Grim flag contributes a fixed unit; its own violation accounting is untouched. */
    public Evidence fromCheck(String checkName, long nowNanos) {
        EvidenceType type = EvidenceType.forCheck(checkName);
        if (type == null || config.grimWeight() <= 0) return null;
        return Evidence.of(type, config.grimWeight(), nowNanos, "grim/" + checkName);
    }

    private double escalation(double probability) {
        double span = 1.0 - config.aiThreshold();
        if (span <= 1.0E-9) return probability >= config.aiThreshold() ? 1.0 : 0.0;
        return Math.max(0, Math.min(1, (probability - config.aiThreshold()) / span));
    }

    private static EvidenceType typeFor(String head) {
        switch (head) {
            case "aimAssist": return EvidenceType.AI_AIM;
            case "killAura": return EvidenceType.AI_KILLAURA;
            case "triggerBot": return EvidenceType.AI_TRIGGER;
            default: return EvidenceType.AI_OVERALL;
        }
    }

    private double clamp(double risk) {
        if (!Double.isFinite(risk)) return 0;
        return Math.max(0, Math.min(config.maxRisk(), risk));
    }
}
