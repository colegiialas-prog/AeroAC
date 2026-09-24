package dev.aeroac.neural.admin.training;

import java.util.function.Consumer;

/**
 * Where the training backend is plugged into the interface, and where the training screens look.
 *
 * <p>One slot, published once during startup by the owner of {@code TrainingServiceClient}. The
 * screens read it, never cache it, and report honestly when it is empty: a training centre that
 * cannot start a run says so instead of drawing a button that does nothing.
 *
 * <p>The presets the interface offers are the ones the server already had — flash and pro — and the
 * dataset name comes from the launcher, so no screen invents either.
 */
public final class TrainingLaunchers {

    /** The presets this interface offers. Names match the configured window presets. */
    public static final String PRESET_FLASH = "flash";

    public static final String PRESET_PRO = "pro";

    private static final Consumer<String> NOWHERE = message -> { };

    private static volatile TrainingLauncher installed;

    private TrainingLaunchers() { }

    /** Publishes the launcher. Passing null withdraws it, which is what a reload does. */
    public static void install(TrainingLauncher launcher) {
        installed = launcher;
    }

    /** The installed launcher, or null when runs cannot be started from here. */
    public static TrainingLauncher orNull() {
        return installed;
    }

    /** Whether a run can be started right now. */
    public static boolean available() {
        TrainingLauncher launcher = installed;
        return launcher != null && launcher.available();
    }

    /** The configured dataset name, or null when no launcher is installed. */
    public static String dataset() {
        TrainingLauncher launcher = installed;
        return launcher == null ? null : launcher.dataset();
    }

    /**
     * Starts a run with one preset.
     *
     * @return true when the request was handed over; false when no launcher is installed or it is
     *         unavailable, in which case the caller shows that rather than a false confirmation
     */
    public static boolean start(String preset, Consumer<String> reply) {
        TrainingLauncher launcher = installed;
        if (launcher == null || !launcher.available()) return false;
        launcher.start(preset, reply == null ? NOWHERE : reply);
        return true;
    }

    /** Cancels the running job. Returns false when there is nothing to ask. */
    public static boolean cancel(Consumer<String> reply) {
        TrainingLauncher launcher = installed;
        if (launcher == null || !launcher.available()) return false;
        launcher.cancel(reply == null ? NOWHERE : reply);
        return true;
    }
}
