package dev.aeroac.neural.admin.training;

import java.util.function.Consumer;

/**
 * The interface-side seam for starting and cancelling a training run.
 *
 * <p>The screens may ask for a run; they may not perform one. Training is the one operation in this
 * plugin that is minutes to hours long, and every part of it — building the request, resolving the
 * preset's window and head list, waiting on a service that lives outside the JVM — belongs to the
 * training client, on its own thread. A click handler that waited for any of it would stall the
 * server the operator is trying to protect.
 *
 * <p>So the call is fire-and-forget: {@link #start} returns immediately and {@code reply} is
 * invoked later, from a thread that is not the interface's main thread, with one operator-facing
 * line. Each preset is one call; the preset's window length, head list, feature schema version and
 * seed are the client's business, because those come from the configured presets and not from a
 * screen.
 *
 * <p>The adapter an owner of {@code TrainingServiceClient} writes is one line per method:
 * {@code client.start(new TrainingRequest(dataset(), preset, window, heads, featureSchemaVersion,
 * seed), reply)} and {@code client.cancel(reply)}. Installing it is what makes the buttons on the
 * training screens real; until then they say the launcher is not connected.
 */
public interface TrainingLauncher {

    /** Whether a run can be started from the interface right now. */
    boolean available();

    /** The dataset the next run will be written to, as configured. */
    String dataset();

    /**
     * Asks for a run with one of the presets the server already defines (flash, pro).
     *
     * <p>Returns immediately; {@code reply} is called later, off the interface's main thread.
     */
    void start(String preset, Consumer<String> reply);

    /** Asks for the running job to be cancelled. Same asynchronous contract as {@link #start}. */
    void cancel(Consumer<String> reply);
}
