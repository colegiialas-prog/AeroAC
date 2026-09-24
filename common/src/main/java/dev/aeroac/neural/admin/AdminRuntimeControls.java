package dev.aeroac.neural.admin;

import java.util.function.Consumer;

/**
 * Where the runtime registers its controlled enable, and where a screen looks for it.
 *
 * <p>A single slot, set once during startup by whoever owns the runtime, read on the interface side
 * by whichever screen offers the button. A screen that finds nothing installed reports that the
 * controlled enable is unavailable instead of drawing a control that cannot work.
 *
 * <p>Nothing here starts, stops or configures anything: it is a reference holder, it does no I/O,
 * and a call through it is exactly as asynchronous as the installed control is.
 */
public final class AdminRuntimeControls {

    private static final Consumer<String> NOWHERE = message -> { };

    private static volatile AdminRuntimeControl installed;

    private AdminRuntimeControls() { }

    /** Publishes the runtime's control. Passing null withdraws it, which is what a stop does. */
    public static void install(AdminRuntimeControl control) {
        installed = control;
    }

    /** The installed control, or null when this server has none. */
    public static AdminRuntimeControl orNull() {
        return installed;
    }

    /** Whether a controlled enable can be offered right now. */
    public static boolean available() {
        AdminRuntimeControl control = installed;
        return control != null && control.available();
    }

    /** Whether the runtime is up according to the installed control. */
    public static boolean enabled() {
        AdminRuntimeControl control = installed;
        return control != null && control.enabled();
    }

    /** The installed control's wording for the current state, or null. */
    public static String stateDetail() {
        AdminRuntimeControl control = installed;
        return control == null ? null : control.stateDetail();
    }

    /**
     * Asks for the runtime to come up.
     *
     * @return true when a request was actually made; false when nothing is installed or the runtime
     *         cannot be controlled here, in which case the caller reports the refusal itself
     */
    public static boolean requestEnable(Consumer<String> reply) {
        AdminRuntimeControl control = installed;
        if (control == null || !control.available()) return false;
        control.requestEnable(reply == null ? NOWHERE : reply);
        return true;
    }

    /** Asks for the runtime to stop. Returns false when there is nothing to ask. */
    public static boolean requestDisable(Consumer<String> reply) {
        AdminRuntimeControl control = installed;
        if (control == null || !control.available()) return false;
        control.requestDisable(reply == null ? NOWHERE : reply);
        return true;
    }
}
