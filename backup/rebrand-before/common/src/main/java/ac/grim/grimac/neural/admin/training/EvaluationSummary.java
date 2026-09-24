package ac.grim.grimac.neural.admin.training;

import java.util.List;
import java.util.Map;

/**
 * Evaluation metrics as they came back from the training service.
 *
 * <p>Every metric is a {@code double} that is allowed to be {@link Double#NaN}, and NaN renders as
 * INSUFFICIENT DATA or UNMEASURABLE — never as zero and never as a flattering default. A detection
 * rate of 0.0 means the model caught nothing; a detection rate of NaN means nobody has measured it.
 * Those are opposite facts and a screen that shows both as "0%" would be actively misleading about
 * whether the anticheat works.
 */
public record EvaluationSummary(String modelVersion, String datasetVersion, int featureSchemaVersion,
                                double rocAuc, double prAuc, double configuredFpr, double tprAtFpr,
                                double falsePositivesPerCombatHour, double medianDetectionSeconds,
                                Calibration calibration, Population population,
                                Map<String, Double> detectionByAssist, Map<String, Double> detectionByClient,
                                Map<String, Double> detectionByScenario, List<String> caveats,
                                long producedAtMillis, String cohortId, Map<String, Double> detectionByPopulation) {
    public EvaluationSummary(String modelVersion, String datasetVersion, int featureSchemaVersion,
                                double rocAuc, double prAuc, double configuredFpr, double tprAtFpr,
                                double falsePositivesPerCombatHour, double medianDetectionSeconds,
                                Calibration calibration, Population population,
                                Map<String, Double> detectionByAssist, Map<String, Double> detectionByClient,
                                Map<String, Double> detectionByScenario, List<String> caveats,
                                long producedAtMillis) {
        this(modelVersion, datasetVersion, featureSchemaVersion, rocAuc, prAuc, configuredFpr, tprAtFpr, falsePositivesPerCombatHour, medianDetectionSeconds, calibration, population, detectionByAssist, detectionByClient, detectionByScenario, caveats, producedAtMillis, null, Map.of());
    }

    public EvaluationSummary {
        detectionByAssist = Map.copyOf(detectionByAssist); detectionByClient = Map.copyOf(detectionByClient);
        detectionByScenario = Map.copyOf(detectionByScenario); detectionByPopulation = Map.copyOf(detectionByPopulation);
        caveats = List.copyOf(caveats);
    }


    /** Whether the reported probabilities were fitted to behave like probabilities at all. */
    public enum Calibration { UNKNOWN, UNCALIBRATED, CALIBRATED }

    /**
     * Which clients the score was measured against.
     *
     * <p>Kept explicit because a number measured on the cheat clients that were in the training set
     * says very little about a client nobody has ever recorded, and reporting the two together
     * would hide exactly the weakness an operator needs to see.
     */
    public enum Population { KNOWN_CLIENT, UNKNOWN_CLIENT, MIXED }

    public static final EvaluationSummary NONE = new EvaluationSummary(null, null, 0,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Calibration.UNKNOWN, Population.MIXED, Map.of(), Map.of(), Map.of(), List.of(), 0);

    public boolean present() { return producedAtMillis > 0; }

    /** True when nothing was measured at all, so the screen shows INSUFFICIENT DATA rather than rows. */
    public boolean empty() {
        return Double.isNaN(rocAuc) && Double.isNaN(prAuc) && Double.isNaN(tprAtFpr)
                && Double.isNaN(falsePositivesPerCombatHour) && Double.isNaN(medianDetectionSeconds);
    }
}
