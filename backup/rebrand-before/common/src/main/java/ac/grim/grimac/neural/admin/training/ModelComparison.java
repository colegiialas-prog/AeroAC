package ac.grim.grimac.neural.admin.training;

import java.util.ArrayList;
import java.util.List;

/**
 * Current model against a candidate, one metric per row.
 *
 * <p>Direction matters and is stated per metric, because "higher is better" is wrong for half of
 * these: a candidate that detects more cheats while producing more false positives per combat hour
 * has not improved, it has moved the cost onto honest players. Rows where a metric got worse are
 * marked as regressions so they cannot be skimmed past.
 *
 * <p>A row where either side is unmeasured is neither an improvement nor a regression. It is a
 * missing measurement, and the only honest thing to do with it is show it as one.
 */
public record ModelComparison(String currentVersion, String candidateVersion, List<Row> rows) {

    public record Row(String metric, double current, double candidate, boolean higherIsBetter, String unit) {
        public boolean measured() { return Double.isFinite(current) && Double.isFinite(candidate); }

        public double delta() { return measured() ? candidate - current : Double.NaN; }

        public boolean regression() {
            if (!measured()) return false;
            double change = delta();
            if (change == 0) return false;
            return higherIsBetter ? change < 0 : change > 0;
        }

        public boolean improvement() {
            return measured() && delta() != 0 && !regression();
        }
    }

    public static final ModelComparison NONE = new ModelComparison(null, null, List.of());

    public boolean present() { return !rows.isEmpty(); }

    public boolean anyRegression() {
        for (Row row : rows) if (row.regression()) return true;
        return false;
    }

    /** True when no row on either side was actually measured; the screen then says UNMEASURABLE. */
    public boolean unmeasured() {
        for (Row row : rows) if (row.measured()) return false;
        return true;
    }

    /**
     * Builds the standard row set from two evaluations.
     *
     * <p>False positives per combat hour comes first on purpose: it is the metric a server operator
     * pays for, and every other number on the screen is only interesting once it is acceptable.
     */
    public static ModelComparison of(EvaluationSummary current, EvaluationSummary candidate) {
        if (current == null) current = EvaluationSummary.NONE;
        if (candidate == null) candidate = EvaluationSummary.NONE;
        if (!current.present() || !candidate.present() || current.cohortId() == null
                || !current.cohortId().equals(candidate.cohortId())
                || current.featureSchemaVersion() != candidate.featureSchemaVersion()
                || current.population() != candidate.population()
                || Double.compare(current.configuredFpr(), candidate.configuredFpr()) != 0) return NONE;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("False positives / combat hour", current.falsePositivesPerCombatHour(),
                candidate.falsePositivesPerCombatHour(), false, "/h"));
        rows.add(new Row("Detection at configured FPR", current.tprAtFpr(), candidate.tprAtFpr(), true, "%"));
        for (String strength : new String[]{"VERY_LOW", "LOW", "MEDIUM", "HIGH"}) {
            rows.add(new Row("Detection " + strength,
                    value(current.detectionByAssist(), strength), value(candidate.detectionByAssist(), strength),
                    true, "%"));
        }
        rows.add(new Row("Median detection time", current.medianDetectionSeconds(),
                candidate.medianDetectionSeconds(), false, "s"));
        rows.add(new Row("ROC-AUC", current.rocAuc(), candidate.rocAuc(), true, ""));
        rows.add(new Row("PR-AUC", current.prAuc(), candidate.prAuc(), true, ""));
        return new ModelComparison(current.modelVersion(), candidate.modelVersion(), List.copyOf(rows));
    }

    private static double value(java.util.Map<String, Double> values, String key) {
        Double found = values == null ? null : values.get(key);
        return found == null ? Double.NaN : found;
    }
}
