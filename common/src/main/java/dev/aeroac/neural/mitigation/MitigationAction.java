package dev.aeroac.neural.mitigation;

import dev.aeroac.neural.risk.RiskState;

/** One mitigation with the state that justified it, so the history explains itself later. */
public record MitigationAction(MitigationRule rule, long startNanos, long endNanos, double riskAtTrigger,
                               RiskState stateAtTrigger, String reason) {

    public MitigationAction {
        if (rule == null || stateAtTrigger == null) throw new IllegalArgumentException("Incomplete mitigation");
        if (endNanos <= startNanos) throw new IllegalArgumentException("Mitigation must have a positive duration");
    }

    public boolean active(long nowNanos) { return nowNanos < endNanos; }

    public long durationMs() { return (endNanos - startNanos) / 1_000_000L; }

    public long remainingMs(long nowNanos) { return Math.max(0, (endNanos - nowNanos) / 1_000_000L); }

    public String describe(long nowNanos) {
        return rule + " risk=" + String.format("%.2f", riskAtTrigger) + " at=" + stateAtTrigger
                + " for=" + durationMs() + "ms remaining=" + remainingMs(nowNanos) + "ms reason=" + reason;
    }
}
