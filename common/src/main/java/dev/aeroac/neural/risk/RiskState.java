package dev.aeroac.neural.risk;

import java.util.Locale;

/**
 * CLEAN/WATCH/SUSPICIOUS/CONFIRMED are derived from the accumulated risk value alone.
 * MITIGATED is never derived: MitigationManager reports it while a mitigation is actually running
 * and the risk-derived state is still below CONFIRMED, so operators can tell "acted on" from "proven".
 */
public enum RiskState {
    CLEAN, WATCH, SUSPICIOUS, MITIGATED, CONFIRMED;

    public static RiskState parse(String name) {
        if (name != null) {
            String cleaned = name.trim().toUpperCase(Locale.ROOT);
            for (RiskState state : values()) if (state.name().equals(cleaned)) return state;
        }
        return MITIGATED;
    }

    public boolean atLeast(RiskState other) { return ordinal() >= other.ordinal(); }
}
