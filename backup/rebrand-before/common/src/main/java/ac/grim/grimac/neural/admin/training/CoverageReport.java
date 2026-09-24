package ac.grim.grimac.neural.admin.training;

import ac.grim.grimac.neural.admin.AdminConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the recorded dataset is thin, measured against goals an operator set themselves.
 *
 * <p>This answers one question — "what have I not recorded much of?" — and refuses the question it
 * looks like it answers. Meeting every goal here does not mean the dataset supports any particular
 * false-positive rate, detection rate or threshold; those are measured by evaluating a model on
 * held-out reviewed data, not by counting sessions. The goals are a planning aid, and the screen
 * says so in as many words.
 */
public record CoverageReport(List<Row> rows, boolean anyShort) {

    /** One counted category. {@code goal} of zero means "no target set", never "satisfied". */
    public record Row(String group, String label, int count, int goal) {
        public boolean measured() { return count >= 0; }
        public boolean shortfall() { return measured() && goal > 0 && count < goal; }
        public double fraction() { return !measured() ? Double.NaN : goal <= 0 ? 1 : Math.min(1.0, count / (double) goal); }
    }

    private static final String[] STRENGTHS = {"VERY_LOW", "LOW", "MEDIUM", "HIGH"};

    public static CoverageReport of(DatasetSummary summary, AdminConfig.Training goals) {
        List<Row> rows = new ArrayList<>();
        int perStrength = goals.sessionsPerAssistStrength();
        for (String strength : STRENGTHS) {
            rows.add(new Row("Assist strength", strength, summary.assistCount(strength), perStrength));
        }
        // UNKNOWN is reported but never given a goal: it is the honest record of a session whose
        // strength nobody wrote down, and asking an operator to collect more of those is nonsense.
        rows.add(new Row("Assist strength", "UNKNOWN", summary.assistCount("UNKNOWN"), 0));

        rows.add(new Row("Cheat clients", "Distinct client families",
                summary.distinctCheatClients(), goals.distinctCheatClients()));

        for (String scenario : goals.scenarios()) {
            int legit = summary.legitScenarios().getOrDefault(scenario, 0);
            int cheat = summary.cheatScenarios().getOrDefault(scenario, 0);
            rows.add(new Row("Scenario", scenario + " (legit/cheat " + legit + "/" + cheat + ")",
                    legit + cheat, 0));
        }

        // High-ping honest play is the case a detector gets wrong most expensively, so it is called
        // out by name. Session metadata carries no ping, only the client protocol, so this row
        // counts what can be counted and the screen labels the limit.
        rows.add(new Row("Legit coverage", "LEGIT median ping >=150ms",
                summary.audit().highPingLegitSessions(), goals.highPingLegitSessions()));

        boolean anyShort = false;
        for (Row row : rows) if (row.shortfall()) anyShort = true;
        return new CoverageReport(List.copyOf(rows), anyShort);
    }
}
