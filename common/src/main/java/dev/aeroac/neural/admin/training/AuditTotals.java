package dev.aeroac.neural.admin.training;

import java.util.Map;

/** Imported only when every counted session matches the offline report's source files. */
public record AuditTotals(long attackWindows, double combatSeconds, Map<String, Integer> legitPingBuckets,
                          int highPingLegitSessions) {
    public static final AuditTotals NONE = new AuditTotals(-1, Double.NaN, Map.of(), -1);
    public AuditTotals { legitPingBuckets = Map.copyOf(legitPingBuckets); }
    public boolean present() { return attackWindows >= 0 && Double.isFinite(combatSeconds); }
}
