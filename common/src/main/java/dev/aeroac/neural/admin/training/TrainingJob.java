package dev.aeroac.neural.admin.training;

/**
 * The last thing an external training service said about a job.
 *
 * <p>A cached snapshot, never a live query: the interface renders whatever arrived last, and a
 * training backend that is slow, unreachable or gone changes nothing about the anticheat. No field
 * here can become a deployed model — see {@link Status}, which has no state past CANDIDATE.
 */
public record TrainingJob(Status status, String jobId, String modelType, String datasetVersion,
                          int featureSchemaVersion, String window, String heads,
                          int epoch, int totalEpochs, double progress, double trainLoss,
                          double validationLoss, long elapsedSeconds, long updatedAtMillis, String message) {

    /**
     * Deliberately stops at CANDIDATE.
     *
     * <p>Training producing a model is not a decision to use it. Promotion is a separate, deliberate
     * act with its own evidence and its own approval, and nothing in this plugin performs it.
     */
    public enum Status {
        /** No training service is configured. The normal state for a server running the anticheat. */
        NOT_CONFIGURED,
        /** Configured but unreachable, or the last poll failed. */
        DISCONNECTED,
        IDLE,
        QUEUED,
        AUDITING,
        PREPARING,
        TRAINING,
        CALIBRATING,
        EVALUATING,
        EXPORTING,
        COMPLETED,
        CANCELLED,
        /** Finished; weights exist. Nothing has been evaluated or promoted by this alone. */
        TRAINED,
        /** Finished and scored. Still not in use anywhere. */
        EVALUATED,
        /** Scored and nominated for a human to consider. Promotion happens outside this plugin. */
        CANDIDATE,
        FAILED
    }

    public static final TrainingJob NOT_CONFIGURED = new TrainingJob(Status.NOT_CONFIGURED, null, null, null,
            0, null, null, 0, 0, Double.NaN, Double.NaN, Double.NaN, 0, 0,
            "Сервис обучения не настроен. Обучение выполняется вне Minecraft-сервера.");

    public static TrainingJob disconnected(String message) {
        return new TrainingJob(Status.DISCONNECTED, null, null, null, 0, null, null, 0, 0,
                Double.NaN, Double.NaN, Double.NaN, 0, System.currentTimeMillis(), message);
    }

    public boolean running() {
        return switch (status) {
            case QUEUED, AUDITING, PREPARING, TRAINING, CALIBRATING, EVALUATING, EXPORTING -> true;
            default -> false;
        };
    }

    public boolean configured() { return status != Status.NOT_CONFIGURED; }
}
