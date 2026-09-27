package dev.aeroac.neural.risk;

import dev.aeroac.neural.NeuralConfig;
import dev.aeroac.neural.inference.PredictionResult;

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

    /**
     * Time passes without decay: the player is in a vehicle and the model cannot see their aim, so
     * silence there is not evidence of honest play.
     */
    public void hold(PlayerRiskProfile profile, long nowNanos) {
        if (nowNanos > profile.lastUpdateNanos()) profile.risk(profile.risk(), nowNanos);
    }

    /**
     * Seeds a fresh profile with risk remembered from an earlier connection, decayed for the time
     * the player was away and capped: the evidence behind it is gone, so it may start a watch but
     * never a verdict on its own.
     */
    public void restore(PlayerRiskProfile profile, double stored, double awaySeconds, long nowNanos) {
        if (!(stored > 0) || !Double.isFinite(stored) || !(awaySeconds >= 0)) return;
        double decayed = stored * Math.exp(-config.decayPerSecond() * awaySeconds);
        profile.risk(clamp(Math.min(config.restoreCap(), decayed)), nowNanos);
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
        return fromPrediction(result, 1.0, nowNanos);
    }

    /**
     * @param share fraction of the window's samples that no earlier scored window already covered.
     *              Overlapping windows see the same ticks; counting each tick once keeps a burst of
     *              heavily overlapping predictions from multiplying one moment into several.
     */
    public Evidence fromPrediction(PredictionResult result, double share, long nowNanos) {
        if (result == null) return null;
        if (!result.calibrated() && !config.acceptUncalibrated()) return null;
        String source = result.model().wireName() + "/" + result.modelVersion();
        if (config.logOdds()) return logOdds(result, share, nowNanos, source);
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

    /**
     * Sequential evidence: a calibrated probability p under base rate prior carries the likelihood
     * ratio logit(p) - logit(prior). Summing those is what a sequential probability ratio test does,
     * so there is no dead band where a cheat tuned to sit at 0.7 earns nothing, and a legitimate
     * window pulls risk down by exactly the evidence it carries. Probabilities are clamped so one
     * saturated answer cannot dominate. Mirrored by ml/aeroml/evaluation/risk_sim.py.
     */
    private Evidence logOdds(PredictionResult result, double share, long nowNanos, String source) {
        double overall = result.overall();
        if (Double.isNaN(overall) || !Double.isFinite(share)) return null;
        double portion = Math.max(0, Math.min(1, share));
        double prior = result.hasCalibrationPrior() ? result.calibrationPrior() : config.aiNeutral();
        prior = Math.max(config.aiClampLow(), Math.min(config.aiClampHigh(), prior));
        double p = Math.max(config.aiClampLow(), Math.min(config.aiClampHigh(), overall));
        double ratio = logit(p) - logit(prior);
        double strength = config.logOddsWeight() * portion * ratio;
        if (ratio < 0) strength *= config.reliefScale();
        if (Math.abs(strength) < 1.0E-12) return null;
        String metadata = "overall=" + String.format("%.3f", overall) + " llr=" + String.format("%+.2f", ratio)
                + " share=" + String.format("%.2f", portion);
        if (strength < 0) return new Evidence(EvidenceType.AI_RELIEF, strength, nowNanos, source, metadata);
        for (String head : new String[]{"aimAssist", "killAura", "triggerBot"}) {
            double probability = result.head(head);
            if (!Double.isNaN(probability) && probability >= config.aiThreshold()) {
                return new Evidence(typeFor(head), strength, nowNanos, source, metadata);
            }
        }
        return new Evidence(EvidenceType.AI_OVERALL, strength, nowNanos, source, metadata);
    }

    private static double logit(double probability) { return Math.log(probability / (1 - probability)); }

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
