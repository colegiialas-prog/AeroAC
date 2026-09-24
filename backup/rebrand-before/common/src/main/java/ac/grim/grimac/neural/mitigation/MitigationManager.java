package ac.grim.grimac.neural.mitigation;

import ac.grim.grimac.neural.NeuralConfig;
import ac.grim.grimac.neural.risk.PlayerRiskProfile;
import ac.grim.grimac.neural.risk.RiskState;

/**
 * Stateless decision logic over PlayerMitigationState. Disabled by default, and even when enabled it
 * refuses to act below the configured state, outside the hourly cap, or once a mitigation is running.
 *
 * <p>Phase 6 is intentionally the last thing switched on: it must follow a measured false positive
 * rate, not a promising probability.
 */
public final class MitigationManager {
    private final NeuralConfig.Mitigation config;

    public MitigationManager(NeuralConfig.Mitigation config) {
        this.config = config;
    }

    public NeuralConfig.Mitigation config() { return config; }

    /** Returns the action just started, or null when nothing changed. Never extends a running one. */
    public MitigationAction evaluate(PlayerMitigationState state, PlayerRiskProfile profile, long nowNanos, String reason) {
        if (!config.enabled() || state == null || profile == null) return null;
        if (state.current(nowNanos) != null) return null;
        RiskState risk = profile.state();
        if (!risk.atLeast(config.minState()) || risk == RiskState.MITIGATED) return null;
        if (!state.admit(nowNanos, config.maxPerHour())) return null;
        MitigationRule rule = config.cancelAttacks() ? MitigationRule.CANCEL_ATTACKS : MitigationRule.OBSERVE;
        MitigationAction action = new MitigationAction(rule, nowNanos,
                nowNanos + config.durationSeconds() * 1_000_000_000L, profile.risk(), risk, reason);
        state.start(action);
        return action;
    }

    /** True only while an action that actually drops attacks is running. */
    public boolean shouldCancelAttacks(PlayerMitigationState state, long nowNanos) {
        if (!config.enabled() || state == null) return false;
        MitigationAction action = state.current(nowNanos);
        return action != null && action.rule() == MitigationRule.CANCEL_ATTACKS;
    }

    /** MITIGATED is reported while an action runs and the risk itself has not reached CONFIRMED. */
    public RiskState report(PlayerMitigationState state, RiskState riskState, long nowNanos) {
        if (state == null || riskState == RiskState.CONFIRMED) return riskState;
        return state.current(nowNanos) == null ? riskState : RiskState.MITIGATED;
    }
}
