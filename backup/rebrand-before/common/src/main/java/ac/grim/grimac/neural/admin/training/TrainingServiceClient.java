package ac.grim.grimac.neural.admin.training;

/**
 * The seam between the anticheat and whatever trains models.
 *
 * <p>The JVM does not train. There is no PyTorch in this process, no subprocess running a training
 * script, and no plan to add either: training is a long, memory-hungry, GPU-bound job, and a
 * Minecraft server is a latency-bound loop that must not stall for it. This interface exists so a
 * future external service can be attached without the interface layer changing shape.
 *
 * <p>Two rules hold for every implementation:
 *
 * <ul>
 *   <li>every getter returns cached data immediately; poll only schedules background I/O,
 *       so callers never perform filesystem or network I/O;</li>
 *   <li>nothing an implementation returns may change the running model, the risk thresholds or the
 *       mitigation configuration. The result of training is a candidate, and promoting a candidate
 *       is a separate decision made by a person.</li>
 * </ul>
 */
public interface TrainingServiceClient extends AutoCloseable {

    /** The last known job state. Must return immediately from cache; never blocks on the network. */
    TrainingJob job();

    /** Metrics for the model currently in production, if any were ever imported. */
    EvaluationSummary currentEvaluation();

    /** Metrics for the newest candidate, if a candidate exists. */
    EvaluationSummary candidateEvaluation();

    /** Asks the backend to refresh its cached state. Returns without waiting for the answer. */
    void poll();

    boolean configured();

    /** Operator-facing reason the service is not usable, or null when it is. */
    String unavailableReason();

    @Override void close();

    /**
     * The default: nothing is configured, and every screen says so.
     *
     * <p>An unconfigured training service is the expected state for a server that is only running
     * the anticheat. It is reported as NOT CONFIGURED rather than hidden, so the absence of numbers
     * on the model screens reads as "nobody has trained anything here" instead of "something broke".
     */
    final class Offline implements TrainingServiceClient {
        @Override public TrainingJob job() { return TrainingJob.NOT_CONFIGURED; }
        @Override public EvaluationSummary currentEvaluation() { return EvaluationSummary.NONE; }
        @Override public EvaluationSummary candidateEvaluation() { return EvaluationSummary.NONE; }
        @Override public void poll() { }
        @Override public boolean configured() { return false; }
        @Override public String unavailableReason() {
            return "No training service is configured. Train with the Python tooling in ml/ and "
                    + "import the evaluation report; the server never trains a model itself.";
        }
        @Override public void close() { }
    }
}
