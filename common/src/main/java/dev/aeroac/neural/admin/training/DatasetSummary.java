package dev.aeroac.neural.admin.training;

import java.util.List;
import java.util.Map;

/**
 * A counted view of everything recorded on this server, built off the main thread and cached.
 *
 * <p>Attack-window, combat-hour and ping totals come only from a complete integrity-bound offline
 * audit of the same metadata set. They remain absent when any selected session is missing or stale;
 * the interface never estimates them or opens raw telemetry. Where a number does not exist, the
 * screen says so.
 */
public record DatasetSummary(int sessions, int players, int legit, int cheat, int unlabeled,
                             int incomplete, int failed, int withDropped, int audited, int reviewed,
                             long totalDurationMs, long totalFrames, long totalRecords,
                             Map<String, Integer> cheatFamilies, Map<String, Integer> clientFamilies,
                             Map<String, Integer> configurations, Map<String, Integer> assistStrengths,
                             Map<String, Integer> cheatScenarios, Map<String, Integer> legitScenarios,
                             Map<String, Integer> legitProtocols, Map<String, Integer> auditVerdicts,
                             List<SessionRecord> recent, List<SessionRecord> attention,
                             boolean truncated, long builtAtMillis, String error, AuditTotals audit) {

    /**
     * The breakdown key for a field nobody filled in.
     *
     * <p>Data, not wording: it is counted and compared, and it is the same in every language. The
     * screens translate it when they draw it.
     */
    public static final String UNSET = "(unset)";

    public DatasetSummary {
        cheatFamilies = Map.copyOf(cheatFamilies); clientFamilies = Map.copyOf(clientFamilies);
        configurations = Map.copyOf(configurations); assistStrengths = Map.copyOf(assistStrengths);
        cheatScenarios = Map.copyOf(cheatScenarios); legitScenarios = Map.copyOf(legitScenarios);
        legitProtocols = Map.copyOf(legitProtocols); auditVerdicts = Map.copyOf(auditVerdicts);
        recent = List.copyOf(recent); attention = List.copyOf(attention);
        if (audit == null) audit = AuditTotals.NONE;
    }
    public DatasetSummary(int sessions, int players, int legit, int cheat, int unlabeled,
                             int incomplete, int failed, int withDropped, int audited, int reviewed,
                             long totalDurationMs, long totalFrames, long totalRecords,
                             Map<String, Integer> cheatFamilies, Map<String, Integer> clientFamilies,
                             Map<String, Integer> configurations, Map<String, Integer> assistStrengths,
                             Map<String, Integer> cheatScenarios, Map<String, Integer> legitScenarios,
                             Map<String, Integer> legitProtocols, Map<String, Integer> auditVerdicts,
                             List<SessionRecord> recent, List<SessionRecord> attention,
                             boolean truncated, long builtAtMillis, String error) {
        this(sessions, players, legit, cheat, unlabeled, incomplete, failed, withDropped, audited, reviewed, totalDurationMs, totalFrames, totalRecords, cheatFamilies, clientFamilies, configurations, assistStrengths, cheatScenarios, legitScenarios, legitProtocols, auditVerdicts, recent, attention, truncated, builtAtMillis, error, AuditTotals.NONE);
    }

    public DatasetSummary withAudit(AuditTotals audit) {
        return new DatasetSummary(sessions, players, legit, cheat, unlabeled, incomplete, failed, withDropped, audited, reviewed, totalDurationMs, totalFrames, totalRecords, cheatFamilies, clientFamilies, configurations, assistStrengths, cheatScenarios, legitScenarios, legitProtocols, auditVerdicts, recent, attention, truncated, builtAtMillis, error, audit);
    }


    /** What every screen shows before the first background read has finished. */
    public static final DatasetSummary PENDING = new DatasetSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(), List.of(), false, 0, null);

    public boolean ready() { return builtAtMillis > 0; }

    public boolean failedToLoad() { return error != null; }

    public long totalDurationSeconds() { return totalDurationMs / 1000L; }

    /** Distinct cheat client families actually recorded — the coverage question that matters most. */
    public int distinctCheatClients() { return (int) clientFamilies.keySet().stream().filter(key -> !key.equals(UNSET) && !key.equalsIgnoreCase("unknown")).count(); }

    public int assistCount(String strength) { return assistStrengths.getOrDefault(strength, 0); }
}
