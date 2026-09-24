package ac.grim.grimac.platform.bukkit.admin.training;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.admin.AdminConfig;
import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminPlayerView;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.admin.RecordingView;
import ac.grim.grimac.platform.bukkit.admin.AeroMenu;
import ac.grim.grimac.platform.bukkit.admin.BukkitAdminGui;
import ac.grim.grimac.platform.bukkit.admin.MenuItems;
import ac.grim.grimac.platform.bukkit.admin.ProfileMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One recording, with the only destructive control in the training centre behind a confirmation.
 *
 * <p>Stopping is two clicks, and the second one is a different button in a different place. A
 * recording session is a person's time: somebody set up a client, agreed to be recorded and has
 * been fighting for four minutes, and losing that to a misclick on a live-refreshing screen is not
 * a recoverable mistake.
 *
 * <p>There is no discard button. Deleting a recorded session from a menu would destroy data the
 * offline tooling may already have indexed, and the file is trivially removable from disk by
 * somebody who has decided they mean it.
 */
public final class RecordingDetailMenu extends AeroMenu {
    private static final int SLOT_HEAD = 4;
    private static final int SLOT_PROGRESS = 20;
    private static final int SLOT_QUALITY = 22;
    private static final int SLOT_STOP = 24;
    private static final int SLOT_CONFIRM = 40;
    private static final int SLOT_PROFILE = 30;

    private final UUID target;
    private boolean confirming;
    private UUID confirmedSession;

    public RecordingDetailMenu(BukkitAdminGui gui, Player viewer, UUID target) {
        super(gui, viewer);
        this.target = target;
    }

    @Override protected String title() { return MenuItems.HEADER + "AERO › RECORDING"; }

    @Override public String permission() { return AdminPermissions.TRAINING; }

    @Override protected int rows() { return 5; }

    @Override protected void draw(Inventory inventory) {
        AdminPlayerView view = service().snapshot().find(target);
        RecordingView recording = view == null ? null : view.recording();
        if (confirming && (recording == null || !recording.sessionId().equals(confirmedSession))) {
            confirming = false;
            confirmedSession = null;
        }
        if (recording == null) {
            inventory.setItem(SLOT_HEAD, MenuItems.item(Material.BARRIER,
                    MenuItems.MUTED + "NO LONGER RECORDING",
                    MenuItems.note("The session closed or the player left.")));
            footer(inventory, this::back, "active recordings");
            return;
        }
        AdminConfig.Training goals = service().config().training();

        List<String> head = new ArrayList<>();
        head.add(MenuItems.line("Session", recording.sessionId().toString().substring(0, 8)));
        head.add(MenuItems.line("State", recording.state().name()));
        head.add(MenuItems.line("Label", recording.label().name(), MenuItems.colourOf(recording.label())));
        head.add(MenuItems.line("Cheat family",
                AdminStyle.blankIfEmpty(recording.cheatFamily(), "(none)")));
        head.add(MenuItems.line("Client", AdminStyle.blankIfEmpty(recording.clientFamily(), "(unset)")));
        head.add(MenuItems.line("Configuration",
                AdminStyle.blankIfEmpty(recording.configuration(), "(unset)")));
        head.add(MenuItems.line("Assist strength", recording.assistStrength() == null
                ? "(unset)" : recording.assistStrength().name()));
        head.add(MenuItems.line("Scenario", AdminStyle.blankIfEmpty(recording.scenario(), "(unset)")));
        inventory.setItem(SLOT_HEAD, MenuItems.head(recording.playerUuid(), recording.playerName(),
                MenuItems.colourOf(recording.label()) + recording.playerName(), head));

        inventory.setItem(SLOT_PROGRESS, MenuItems.item(Material.CLOCK, MenuItems.HEADER + "PROGRESS",
                MenuItems.LABEL + "Duration: " + MenuItems.VALUE
                        + AdminStyle.clock(recording.durationSeconds()) + MenuItems.MUTED + " / "
                        + AdminStyle.clock(goals.targetDurationSeconds()),
                MenuItems.GOOD + AdminStyle.bar(
                        recording.durationProgress(goals.targetDurationSeconds()), 20),
                "",
                MenuItems.LABEL + "Attack windows: " + MenuItems.VALUE + recording.attackWindows()
                        + MenuItems.MUTED + " / " + goals.targetAttackWindows(),
                MenuItems.GOOD + AdminStyle.bar(
                        recording.windowProgress(goals.targetAttackWindows()), 20),
                "",
                MenuItems.line("Frames", String.valueOf(recording.frames())),
                MenuItems.line("Records", String.valueOf(recording.records())),
                MenuItems.line("Attacks", String.valueOf(recording.attacks())),
                MenuItems.line("Swings", String.valueOf(recording.swings()))));

        List<String> quality = new ArrayList<>();
        quality.add(MenuItems.line("Dropped records", String.valueOf(recording.dropped()),
                recording.dropped() > 0 ? MenuItems.BAD : MenuItems.GOOD));
        quality.add(MenuItems.line("Queued for disk", String.valueOf(recording.queued())));
        quality.add(MenuItems.line("Movement gaps", String.valueOf(recording.movementGaps())));
        quality.add(MenuItems.note("Known values: last 128 recorded frames."));
        quality.add(MenuItems.line("Target known", AdminStyle.percent(recording.targetKnown())));
        quality.add(MenuItems.line("Aim error known", AdminStyle.percent(recording.aimErrorKnown())));
        quality.add(MenuItems.line("Geometry known", AdminStyle.percent(recording.geometryKnown())));
        quality.add("");
        if (recording.qualityWarning()) quality.add(MenuItems.WARN + "QUALITY WARNING");
        quality.addAll(MenuItems.wrap(MenuItems.MUTED,
                "These are counters, not a verdict. Whether this session is GOOD, needs REVIEW or "
                        + "is UNUSABLE is decided by the offline audit, which reads the telemetry itself.", 44));
        inventory.setItem(SLOT_QUALITY, MenuItems.item(
                recording.qualityWarning() ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                MenuItems.HEADER + "LIVE QUALITY", quality));

        inventory.setItem(SLOT_PROFILE, MenuItems.item(Material.ENDER_EYE,
                MenuItems.HEADER + "PLAYER PROFILE",
                MenuItems.note("What the detector currently makes of them.")));

        inventory.setItem(SLOT_STOP, MenuItems.item(
                confirming ? Material.RED_STAINED_GLASS_PANE : Material.REDSTONE,
                confirming ? MenuItems.BAD + "STOP — CONFIRM BELOW" : MenuItems.WARN + "STOP RECORDING",
                MenuItems.note(confirming
                        ? "Click the confirm button at the bottom."
                        : "Closes the session and flushes it to disk.")));

        if (confirming) {
            inventory.setItem(SLOT_CONFIRM, MenuItems.item(Material.RED_CONCRETE,
                    MenuItems.BAD + "CONFIRM STOP",
                    MenuItems.line("Player", recording.playerName()),
                    MenuItems.line("Recorded", AdminStyle.clock(recording.durationSeconds())),
                    "",
                    MenuItems.note("The data recorded so far is kept.")));
        }
        footer(inventory, this::back, "active recordings");
    }

    @Override public void click(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (isFooterClose(slot, rows())) {
            viewer.closeInventory();
            return;
        }
        if (isFooterBack(slot, rows())) {
            back();
            return;
        }
        if (slot == SLOT_PROFILE) {
            gui.show(new ProfileMenu(gui, viewer, target, false));
            return;
        }
        if (slot == SLOT_STOP) {
            if (!permitted(AdminPermissions.TRAINING_STOP)) {
                deny(AdminPermissions.TRAINING_STOP);
                return;
            }
            confirming = !confirming;
            var view = service().snapshot().find(target);
            confirmedSession = confirming && view != null && view.recording() != null
                    ? view.recording().sessionId() : null;
            confirming = confirmedSession != null;
            redraw();
            return;
        }
        if (slot == SLOT_CONFIRM && confirming) {
            if (!permitted(AdminPermissions.TRAINING_STOP)) {
                deny(AdminPermissions.TRAINING_STOP);
                return;
            }
            stop();
        }
    }

    /** Same call the command makes, on the target's event loop, with the same close reason. */
    private void stop() {
        var player = gui.tracked(target);
        if (player == null) {
            viewer.sendMessage(MenuItems.MUTED + "That player is no longer online; the session closed with them.");
            back();
            return;
        }
        UUID expectedSession = confirmedSession;
        long epoch = service().generation();
        var recipient = senderOf();
        player.runSafely(() -> {
            if (epoch != service().generation()) return;
            var current = player.getNeuralState().session;
            if (current == null || !current.metadata.sessionId().equals(expectedSession) || !current.accepting()) {
                ac.grim.grimac.neural.NeuralMessages.send(recipient, "The selected session already closed; nothing was stopped.");
                return;
            }
            player.getNeuralState().stop("MANUAL_STOP");
            ac.grim.grimac.neural.NeuralMessages.send(recipient, "Recording stopped for " + player.getName()
                    + "; queued data is flushing asynchronously.");
        });
        service().invalidateDatasetSummary();
        confirming = false;
        back();
    }

    private void back() {
        gui.show(new RecordingsMenu(gui, viewer));
    }
}
