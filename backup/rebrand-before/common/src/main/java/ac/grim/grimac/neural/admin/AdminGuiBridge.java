package ac.grim.grimac.neural.admin;

import ac.grim.grimac.platform.api.sender.Sender;

import java.util.UUID;

/**
 * How a command opens a screen without knowing what a screen is.
 *
 * <p>The inventory API is per platform and there is no honest way to abstract it: Bukkit has
 * {@code Inventory} and an {@code InventoryHolder}, a Fabric server has neither in that shape, and
 * pretending otherwise produces a façade that fits one of them badly. So the split is here instead.
 * Everything above this line — the view models, the sorting, the pagination arithmetic, the
 * validation, the dataset summary, the alerts, the indicators — is platform-neutral and lives in
 * common. Only the drawing is behind this interface.
 *
 * <p>A platform with no implementation returns {@link #UNAVAILABLE}, and every command falls back to
 * text output rather than failing. That is what keeps the command surface honest on a platform that
 * has no menus yet.
 */
public interface AdminGuiBridge {

    /** The no-op used before a platform registers one, and on platforms that have no menus. */
    AdminGuiBridge UNAVAILABLE = new AdminGuiBridge() {
        @Override public boolean available() { return false; }
        @Override public void openMain(Sender sender) { }
        @Override public void openPlayers(Sender sender, boolean suspiciousOnly) { }
        @Override public void openProfile(Sender sender, UUID target) { }
        @Override public void openTraining(Sender sender) { }
        @Override public void openRecordings(Sender sender) { }
        @Override public void closeAll() { }
    };

    boolean available();

    void openMain(Sender sender);

    void openPlayers(Sender sender, boolean suspiciousOnly);

    void openProfile(Sender sender, UUID target);

    void openTraining(Sender sender);

    void openRecordings(Sender sender);

    default void resumeRecording(Sender sender, String field) { openTraining(sender); }

    default void reload() { closeAll(); }

    /** Closes every open screen, so none survives a reload of the state it was drawn from. */
    void closeAll();
}
