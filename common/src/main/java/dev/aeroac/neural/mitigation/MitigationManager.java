package dev.aeroac.neural.mitigation;

import dev.aeroac.neural.NeuralConfig;
import dev.aeroac.neural.risk.PlayerRiskProfile;
import dev.aeroac.neural.risk.RiskState;

/**
 * Stateless decision logic over PlayerMitigationState. Disabled by default, and even when enabled it
 * refuses to act below the configured state, outside the hourly cap, or once a mitigation is running.
 *
 * <p>Phase 6 is intentionally the last thing switched on: it must follow a measured false positive
 * rate, not a promising probability.
 */
public final class MitigationManager {
    private final NeuralConfig.Mitigation config;
    /** Uniform [0,1). Injected so tests can pin the random onset, jitter and cancel draws. */
    private final java.util.function.DoubleSupplier random;

    public MitigationManager(NeuralConfig.Mitigation config) {
        this(config, () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    public MitigationManager(NeuralConfig.Mitigation config, java.util.function.DoubleSupplier random) {
        this.config = config;
        this.random = random;
    }

    public NeuralConfig.Mitigation config() { return config; }

    /** Returns the action just started, or null when nothing changed. Never extends a running one. */
    public MitigationAction evaluate(PlayerMitigationState state, PlayerRiskProfile profile, long nowNanos, String reason) {
        if (!config.enabled() || state == null || profile == null) return null;
        if (state.current(nowNanos) != null) return null;
        RiskState risk = profile.state();
        if (!risk.atLeast(config.minState()) || risk == RiskState.MITIGATED) return null;
        if (!state.admit(nowNanos, config.maxPerHour())) return null;
        MitigationRule rule = !config.affectsGameplay() ? MitigationRule.OBSERVE
                : config.cancelChance() >= 1 ? MitigationRule.CANCEL_ATTACKS : MitigationRule.DAMPEN;
        long onset = seconds(config.onsetDelayMinSeconds()
                + random.getAsDouble() * (config.onsetDelayMaxSeconds() - config.onsetDelayMinSeconds()));
        long effective = nowNanos + onset;
        long end = effective + config.durationSeconds() * 1_000_000_000L
                + seconds(random.getAsDouble() * config.releaseJitterSeconds());
        MitigationAction action = new MitigationAction(rule, nowNanos, effective, end, profile.risk(), risk, reason,
                rule == MitigationRule.OBSERVE ? 0 : config.cancelChance(),
                rule == MitigationRule.OBSERVE ? 1 : config.damageMultiplier());
        state.start(action);
        return action;
    }

    private static long seconds(double value) { return (long) (value * 1_000_000_000L); }

    /**
     * Decides for one attack packet. Drops it with the action's cancel chance, drawn per attack, so a
     * dampened player loses a share of hits the way lag would take them, not all of them at once.
     */
    public boolean shouldCancelAttacks(PlayerMitigationState state, long nowNanos) {
        if (!config.enabled() || state == null) return false;
        MitigationAction action = state.current(nowNanos);
        if (action == null || !action.enforcing(nowNanos) || action.cancelChance() <= 0) return false;
        return action.cancelChance() >= 1 || random.getAsDouble() < action.cancelChance();
    }

    /** Factor on the damage this player deals right now; 1 when no enforcing action scales it. */
    public double damageMultiplier(PlayerMitigationState state, long nowNanos) {
        if (!config.enabled() || state == null) return 1.0;
        MitigationAction action = state.current(nowNanos);
        return action == null || !action.enforcing(nowNanos) ? 1.0 : action.damageMultiplier();
    }

    /** MITIGATED is reported while an action runs and the risk itself has not reached CONFIRMED. */
    public RiskState report(PlayerMitigationState state, RiskState riskState, long nowNanos) {
        if (state == null || riskState == RiskState.CONFIRMED) return riskState;
        return state.current(nowNanos) == null ? riskState : RiskState.MITIGATED;
    }
}
