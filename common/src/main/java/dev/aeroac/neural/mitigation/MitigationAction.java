package dev.aeroac.neural.mitigation;

import dev.aeroac.neural.risk.RiskState;

/**
 * One mitigation with the state that justified it, so the history explains itself later. It is
 * decided at startNanos but only enforced from effectiveNanos (a random onset delay), and it drops
 * cancelChance of the player's attacks and scales their damage by damageMultiplier until endNanos.
 */
public record MitigationAction(MitigationRule rule, long startNanos, long effectiveNanos, long endNanos,
                               double riskAtTrigger, RiskState stateAtTrigger, String reason,
                               double cancelChance, double damageMultiplier) {

    public MitigationAction {
        if (rule == null || stateAtTrigger == null) throw new IllegalArgumentException("Incomplete mitigation");
        if (endNanos <= startNanos) throw new IllegalArgumentException("Mitigation must have a positive duration");
        if (effectiveNanos < startNanos || effectiveNanos > endNanos) {
            throw new IllegalArgumentException("Mitigation must take effect inside its own span");
        }
        if (!(cancelChance >= 0 && cancelChance <= 1) || !(damageMultiplier > 0 && damageMultiplier <= 1)) {
            throw new IllegalArgumentException("Mitigation effect out of range");
        }
    }

    /** An immediate action with the effect its rule implies: all attacks dropped, or none. */
    public MitigationAction(MitigationRule rule, long startNanos, long endNanos, double riskAtTrigger,
                            RiskState stateAtTrigger, String reason) {
        this(rule, startNanos, startNanos, endNanos, riskAtTrigger, stateAtTrigger, reason,
                rule == MitigationRule.CANCEL_ATTACKS ? 1.0 : 0.0, 1.0);
    }

    /** Decided and not yet expired; it may still be waiting out its onset delay. */
    public boolean active(long nowNanos) { return nowNanos < endNanos; }

    /** Actually changing gameplay right now. */
    public boolean enforcing(long nowNanos) { return nowNanos >= effectiveNanos && nowNanos < endNanos; }

    public long durationMs() { return (endNanos - startNanos) / 1_000_000L; }

    public long remainingMs(long nowNanos) { return Math.max(0, (endNanos - nowNanos) / 1_000_000L); }

    public String describe(long nowNanos) {
        String effect = rule == MitigationRule.DAMPEN
                ? String.format(" cancel=%.0f%% damage=x%.2f", cancelChance * 100, damageMultiplier) : "";
        String pending = nowNanos < effectiveNanos ? " starts-in=" + (effectiveNanos - nowNanos) / 1_000_000L + "ms" : "";
        return rule + effect + " risk=" + String.format("%.2f", riskAtTrigger) + " at=" + stateAtTrigger
                + " for=" + durationMs() + "ms remaining=" + remainingMs(nowNanos) + "ms" + pending + " reason=" + reason;
    }
}
