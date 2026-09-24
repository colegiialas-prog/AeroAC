package dev.aeroac.neural.admin.training;

import dev.aeroac.locale.AeroMessages;

/**
 * Russian wording and tone for a training job's status.
 *
 * <p>The status constants stay exactly as they are — {@code QUEUED}, {@code TRAINING},
 * {@code CANDIDATE} and the rest are written into the job snapshot, matched by the external tooling
 * and read back by the audit, so they are data and not copy. What an operator reads is this: the
 * wording for the state, and how loudly to show it.
 *
 * <p>Mapping is done by name rather than by a switch over the enum on purpose. The status set grows
 * as the training backend learns to report more of its own stages — auditing a dataset, preparing
 * tensors, calibrating, exporting — and a name-based map keeps a new stage rendering as its own
 * stage instead of failing to compile or falling into a wrong branch. A status this class has never
 * heard of shows its own name, which is exactly what an operator should see for a state the
 * interface has not been taught yet.
 *
 * <p>Nothing here claims a finished job is a deployed model. The loudest tone is reserved for
 * failure and for cancellation, never for success.
 */
public final class TrainingStatus {

    /** How insistently a state should be drawn. Colours are chosen by the platform layer. */
    public enum Tone {
        /** Nothing to see: unconfigured, idle, or a state nobody recognises. */
        MUTED,
        /** In progress right now. */
        ACTIVE,
        /** Finished, and worth looking at. */
        GOOD,
        /** Failed, cancelled, or unreachable. */
        BAD
    }

    private TrainingStatus() { }

    /** The catalog key for a status, never null. */
    public static String key(String statusName) {
        if (statusName == null || statusName.isBlank()) return "admin.training.status.unknown";
        return switch (statusName) {
            case "NOT_CONFIGURED" -> "admin.training.status.not_configured";
            case "DISCONNECTED" -> "admin.training.status.disconnected";
            case "IDLE" -> "admin.training.status.idle";
            case "QUEUED" -> "admin.training.status.queued";
            case "PREPARING" -> "admin.training.status.preparing";
            case "AUDITING" -> "admin.training.status.auditing";
            case "TRAINING" -> "admin.training.status.training";
            case "CALIBRATING" -> "admin.training.status.calibrating";
            case "EVALUATING" -> "admin.training.status.evaluating";
            case "EXPORTING" -> "admin.training.status.exporting";
            case "TRAINED" -> "admin.training.status.trained";
            case "EVALUATED" -> "admin.training.status.evaluated";
            case "COMPLETED" -> "admin.training.status.completed";
            case "CANDIDATE" -> "admin.training.status.candidate";
            case "CANCELLED" -> "admin.training.status.cancelled";
            case "FAILED" -> "admin.training.status.failed";
            default -> "admin.training.status.unknown";
        };
    }

    /** The catalog key for a status. */
    public static String key(TrainingJob.Status status) {
        return key(status == null ? null : status.name());
    }

    /**
     * The wording for a state, falling back to the state's own name.
     *
     * <p>A status with no translation shows its constant rather than a blank or a guess: an unknown
     * stage is information, and hiding it would be the one thing worse than an untranslated word.
     */
    public static String label(String statusName) {
        String key = key(statusName);
        String translated = AeroMessages.tr(key);
        if (!translated.equals(key)) return translated;
        return statusName == null ? AeroMessages.tr("admin.training.status.unknown") : statusName;
    }

    /** The wording for a state. */
    public static String label(TrainingJob.Status status) {
        return label(status == null ? null : status.name());
    }

    /** How loudly to draw a state. */
    public static Tone tone(String statusName) {
        if (statusName == null) return Tone.MUTED;
        return switch (statusName) {
            case "QUEUED", "PREPARING", "AUDITING", "TRAINING", "CALIBRATING", "EVALUATING", "EXPORTING" -> Tone.ACTIVE;
            case "TRAINED", "EVALUATED", "COMPLETED", "CANDIDATE" -> Tone.GOOD;
            case "FAILED", "DISCONNECTED", "CANCELLED" -> Tone.BAD;
            default -> Tone.MUTED;
        };
    }

    /** How loudly to draw a state. */
    public static Tone tone(TrainingJob.Status status) {
        return tone(status == null ? null : status.name());
    }

    /** Whether this state means work is happening right now. */
    public static boolean working(String statusName) {
        return tone(statusName) == Tone.ACTIVE;
    }
}
