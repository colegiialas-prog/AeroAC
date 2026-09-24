package dev.aeroac.neural.admin;

import dev.aeroac.neural.risk.RiskState;

import java.util.Locale;

/** What an administrator has asked to see floating above players. Never visible to anyone else. */
public enum AdminViewMode {
    /** No indicator at all. */
    OFF,
    /** Every tracked player, including CLEAN ones. */
    ALL,
    /** Only players at or above WATCH. */
    SUSPICIOUS,
    /**
     * Same filter as SUSPICIOUS, but the indicator is removed the moment a player returns to CLEAN
     * rather than waiting for the administrator to look away. Kept separate so the two can diverge.
     */
    AUTO;

    public static AdminViewMode parse(String name, AdminViewMode fallback) {
        if (name != null) {
            String cleaned = name.trim().toUpperCase(Locale.ROOT);
            for (AdminViewMode mode : values()) if (mode.name().equals(cleaned)) return mode;
        }
        return fallback;
    }

    /** Whether this mode wants an indicator for a player in the given reported state. */
    public boolean shows(RiskState state) {
        return switch (this) {
            case OFF -> false;
            case ALL -> true;
            case SUSPICIOUS, AUTO -> state != null && state.atLeast(RiskState.WATCH);
        };
    }

    public String lower() { return name().toLowerCase(Locale.ROOT); }
}
