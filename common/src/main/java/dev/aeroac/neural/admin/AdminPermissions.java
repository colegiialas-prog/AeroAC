package dev.aeroac.neural.admin;

/**
 * Permission nodes for the administrator interface.
 *
 * <p>Split rather than a single node because these are not equally dangerous. Reading a profile is
 * not the same as opening a dataset recording, and neither is the same as touching a review verdict
 * that Python tooling will later treat as ground truth. Every node implies nothing: a server that
 * wants one operator to only look grants {@link #GUI} and {@link #PLAYERS} and stops there.
 */
public final class AdminPermissions {
    /** Everything below it, for a server that does not want to think about the split. */
    public static final String ADMIN = "aero.admin";

    public static final String GUI = "aero.gui";
    public static final String PLAYERS = "aero.players";
    public static final String SUSPICIOUS = "aero.suspicious";
    public static final String PROFILE = "aero.profile";
    public static final String MONITOR = "aero.monitor";
    public static final String VIEW = "aero.view";
    public static final String STATUS = "aero.status";
    public static final String MITIGATION = "aero.mitigation";
    public static final String ALERTS = "aero.alerts";

    /** See what the anticheat would ban, and what it did ban. */
    public static final String ENFORCE = "aero.enforce";
    /** Confirm or decline a verdict. Held apart: this is the node that removes a player. */
    public static final String ENFORCE_CONFIRM = "aero.enforce.confirm";

    public static final String TRAINING = "aero.training";
    /** Opening a dataset session: this writes labelled data that a model will be trained on. */
    public static final String TRAINING_RECORD = "aero.training.record";
    public static final String TRAINING_STOP = "aero.training.stop";
    public static final String TRAINING_OVERVIEW = "aero.training.overview";
    /** Recording a human review verdict. Held apart because the verdict is treated as truth later. */
    public static final String TRAINING_REVIEW = "aero.training.review";
    public static final String TRAINING_MODEL = "aero.training.model";

    private AdminPermissions() { }
}
