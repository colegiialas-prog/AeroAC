package dev.aeroac.neural.admin;

import java.util.function.Consumer;

/**
 * The interface-side seam for switching the neural runtime on and off.
 *
 * <p>A screen may ask for the runtime to be enabled, and it may not do it itself. Enabling builds the
 * runtime: it opens the dataset storage, creates the inference client and schedules work on threads
 * the screen knows nothing about. Doing that inside a click handler would stall the tick the operator
 * is standing in, which is the one thing the admin interface must never do.
 *
 * <p>So the interface asks, and the runtime answers later. {@link #requestEnable} schedules the work
 * on the runtime's own executor and returns immediately; the reply consumer is invoked later, from
 * the runtime's thread, with a line to show the operator. A platform implementation that owns a main
 * thread must hop back before touching any inventory.
 *
 * <p>An implementation is not required to exist. A server whose runtime is wired at startup has
 * nothing to switch, and there {@link AdminRuntimeControls#available()} stays false and the screens
 * say so rather than pretending a button would work.
 */
public interface AdminRuntimeControl {

    /** Whether a controlled enable is possible on this server right now. */
    boolean available();

    /** Whether the runtime is currently up, as last reported to the interface. */
    boolean enabled();

    /** Operator-facing wording for the current state, or {@code null} when there is nothing useful to say. */
    String stateDetail();

    /**
     * Asks the runtime to come up, without waiting for it.
     *
     * <p>Returns immediately. {@code reply} receives one operator-facing line when the attempt
     * finishes, from a thread that is not the interface's main thread.
     */
    void requestEnable(Consumer<String> reply);

    /** Asks the runtime to stop cleanly. Asynchronous, exactly as {@link #requestEnable}. */
    void requestDisable(Consumer<String> reply);
}
