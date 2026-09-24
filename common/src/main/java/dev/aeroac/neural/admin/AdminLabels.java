package dev.aeroac.neural.admin;

import dev.aeroac.locale.AeroMessages;
import dev.aeroac.neural.admin.training.EvaluationSummary;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.neural.risk.RiskState;

/**
 * The words for the internal vocabulary, wherever the interface has to show one.
 *
 * <p>{@code CLEAN}, {@code SUSPICIOUS}, {@code LEGIT}, {@code MEDIUM} and the rest are data: they
 * are written into dataset rows, compared by the audit and matched by the training tooling, so the
 * constants keep their names and nothing here renames an enum. What an operator reads is the word
 * for it — the state a player is in, the label a session carries, how much help a cheat was set to
 * — and that word comes from the catalog like every other piece of copy.
 *
 * <p>A value with no translation keeps its code rather than showing a blank. That is deliberate: an
 * operator looking at a state nobody has taught the interface yet should see which state it is, not
 * an empty cell, and the raw code is the one thing that is certainly true.
 */
public final class AdminLabels {

    private AdminLabels() { }

    /**
     * The word for a coverage group.
     *
     * <p>The report is built in common, where the row keeps a stable key so tests and future
     * tooling can match on it. The word an operator reads is looked up here, at the edge.
     */
    public static String coverageGroup(String key) {
        if (key == null) return AdminStyle.NO_DATA;
        return resolve("gui.coverage.group." + key, key);
    }

    /** The word for one coverage row, for the rows whose label is prose rather than a code. */
    public static String coverageLabel(String key) {
        if (key == null) return AdminStyle.NO_DATA;
        return resolve("gui.coverage.label." + key, key);
    }

    /** The name of a metric on the model comparison screen. */
    public static String comparisonMetric(String key) {
        if (key == null) return AdminStyle.NO_DATA;
        return resolve("gui.compare.metric." + key, key);
    }

    /** The word for a player's state. */
    public static String state(RiskState state) {
        if (state == null) return AdminStyle.NO_DATA;
        return resolve(switch (state) {
            case CLEAN -> "gui.state.clean";
            case WATCH -> "gui.state.watch";
            case SUSPICIOUS -> "gui.state.suspicious";
            case MITIGATED -> "gui.state.mitigated";
            case CONFIRMED -> "gui.state.confirmed";
        }, state.name());
    }

    /** The word for the label a recording was taken under. */
    public static String datasetLabel(DatasetMetadata.Label label) {
        if (label == null) return AdminStyle.NO_DATA;
        return resolve(switch (label) {
            case LEGIT -> "gui.dataset_label.legit";
            case CHEAT -> "gui.dataset_label.cheat";
            case UNLABELED -> "gui.dataset_label.unlabeled";
        }, label.name());
    }

    /** The word for how much assistance a cheat session was set to. */
    public static String assistStrength(DatasetMetadata.AssistStrength strength) {
        if (strength == null) return AdminStyle.NO_DATA;
        return resolve(switch (strength) {
            case NONE -> "gui.strength.none";
            case VERY_LOW -> "gui.strength.very_low";
            case LOW -> "gui.strength.low";
            case MEDIUM -> "gui.strength.medium";
            case HIGH -> "gui.strength.high";
            case UNKNOWN -> "gui.strength.unknown";
        }, strength.name());
    }

    /** The word for the indicator mode an administrator chose. */
    public static String viewMode(AdminViewMode mode) {
        if (mode == null) return AdminStyle.NO_DATA;
        return resolve(switch (mode) {
            case OFF -> "gui.mode.off";
            case ALL -> "gui.mode.all";
            case SUSPICIOUS -> "gui.mode.suspicious";
            case AUTO -> "gui.mode.auto";
        }, mode.name());
    }

    /** The word for how a recording is doing. */
    public static String recording(RecordingView.State state) {
        if (state == null) return AdminStyle.NO_DATA;
        return resolve(switch (state) {
            case RECORDING -> "gui.recording_state.recording";
            case CLOSING -> "gui.recording_state.closing";
            case FAILED -> "gui.recording_state.failed";
        }, state.name());
    }

    /** The word for whether a score was calibrated against a labelled dataset. */
    public static String calibration(EvaluationSummary.Calibration calibration) {
        if (calibration == null) return AdminStyle.NO_DATA;
        return resolve(switch (calibration) {
            case CALIBRATED -> "gui.calibration.calibrated";
            case UNCALIBRATED -> "gui.calibration.uncalibrated";
            case UNKNOWN -> "gui.calibration.unknown";
        }, calibration.name());
    }

    /** The word for which clients an evaluation was measured against. */
    public static String population(EvaluationSummary.Population population) {
        if (population == null) return AdminStyle.NO_DATA;
        return resolve(switch (population) {
            case KNOWN_CLIENT -> "gui.population.known_client";
            case UNKNOWN_CLIENT -> "gui.population.unknown_client";
            case MIXED -> "gui.population.mixed";
        }, population.name());
    }

    private static String resolve(String key, String fallback) {
        String word = AeroMessages.tr(key);
        return word.equals(key) ? fallback : word;
    }
}
