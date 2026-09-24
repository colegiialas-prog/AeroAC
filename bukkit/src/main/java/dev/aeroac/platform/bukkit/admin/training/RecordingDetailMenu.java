package dev.aeroac.platform.bukkit.admin.training;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.admin.AdminConfig;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.admin.RecordingView;
import dev.aeroac.platform.bukkit.admin.AeroMenu;
import dev.aeroac.platform.bukkit.admin.BukkitAdminGui;
import dev.aeroac.platform.bukkit.admin.MenuItems;
import dev.aeroac.platform.bukkit.admin.ProfileMenu;
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

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.training.aero_recording"); }

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
                    MenuItems.MUTED + AeroMessages.tr("gui.training.no_longer_recording"),
                    MenuItems.note(AeroMessages.tr("gui.training.the_session_closed_or_the_player_left"))));
            footer(inventory, this::back, AeroMessages.tr("gui.training.active_recordings"));
            return;
        }
        AdminConfig.Training goals = service().config().training();

        List<String> head = new ArrayList<>();
        head.add(MenuItems.line(AeroMessages.tr("gui.training.session"), recording.sessionId().toString().substring(0, 8)));
        head.add(MenuItems.line(AeroMessages.tr("gui.training.state"), AdminLabels.recording(recording.state())));
        head.add(MenuItems.line(AeroMessages.tr("gui.training.label"), AdminLabels.datasetLabel(recording.label()), MenuItems.colourOf(recording.label())));
        head.add(MenuItems.line(AeroMessages.tr("gui.training.cheat_family"),
                AdminStyle.blankIfEmpty(recording.cheatFamily(), AeroMessages.tr("gui.training.none_2"))));
        head.add(MenuItems.line(AeroMessages.tr("gui.training.client"), AdminStyle.blankIfEmpty(recording.clientFamily(), AeroMessages.tr("gui.training.unset"))));
        head.add(MenuItems.line(AeroMessages.tr("gui.training.configuration"),
                AdminStyle.blankIfEmpty(recording.configuration(), AeroMessages.tr("gui.training.unset"))));
        head.add(MenuItems.line(AeroMessages.tr("gui.training.assist_strength"), recording.assistStrength() == null
                ? AeroMessages.tr("gui.training.unset") : AdminLabels.assistStrength(recording.assistStrength())));
        head.add(MenuItems.line(AeroMessages.tr("gui.training.scenario"), AdminStyle.blankIfEmpty(recording.scenario(), AeroMessages.tr("gui.training.unset"))));
        inventory.setItem(SLOT_HEAD, MenuItems.head(recording.playerUuid(), recording.playerName(),
                MenuItems.colourOf(recording.label()) + recording.playerName(), head));

        inventory.setItem(SLOT_PROGRESS, MenuItems.item(Material.CLOCK, MenuItems.HEADER + AeroMessages.tr("gui.training.progress"),
                MenuItems.LABEL + AeroMessages.tr("gui.training.duration_2") + MenuItems.VALUE
                        + AdminStyle.clock(recording.durationSeconds()) + MenuItems.MUTED + " / "
                        + AdminStyle.clock(goals.targetDurationSeconds()),
                MenuItems.GOOD + AdminStyle.bar(
                        recording.durationProgress(goals.targetDurationSeconds()), 20),
                "",
                MenuItems.LABEL + AeroMessages.tr("gui.training.attack_windows_2") + MenuItems.VALUE + recording.attackWindows()
                        + MenuItems.MUTED + " / " + goals.targetAttackWindows(),
                MenuItems.GOOD + AdminStyle.bar(
                        recording.windowProgress(goals.targetAttackWindows()), 20),
                "",
                MenuItems.line(AeroMessages.tr("gui.training.frames"), String.valueOf(recording.frames())),
                MenuItems.line(AeroMessages.tr("gui.training.records"), String.valueOf(recording.records())),
                MenuItems.line(AeroMessages.tr("gui.training.attacks"), String.valueOf(recording.attacks())),
                MenuItems.line(AeroMessages.tr("gui.training.swings"), String.valueOf(recording.swings()))));

        List<String> quality = new ArrayList<>();
        quality.add(MenuItems.line(AeroMessages.tr("gui.training.dropped_records"), String.valueOf(recording.dropped()),
                recording.dropped() > 0 ? MenuItems.BAD : MenuItems.GOOD));
        quality.add(MenuItems.line(AeroMessages.tr("gui.training.queued_for_disk"), String.valueOf(recording.queued())));
        quality.add(MenuItems.line(AeroMessages.tr("gui.training.movement_gaps"), String.valueOf(recording.movementGaps())));
        quality.add(MenuItems.note(AeroMessages.tr("gui.training.known_values_last_128_recorded_frames")));
        quality.add(MenuItems.line(AeroMessages.tr("gui.training.target_known"), AdminStyle.percent(recording.targetKnown())));
        quality.add(MenuItems.line(AeroMessages.tr("gui.training.aim_error_known"), AdminStyle.percent(recording.aimErrorKnown())));
        quality.add(MenuItems.line(AeroMessages.tr("gui.training.geometry_known"), AdminStyle.percent(recording.geometryKnown())));
        quality.add("");
        if (recording.qualityWarning()) quality.add(MenuItems.WARN + AeroMessages.tr("gui.training.quality_warning"));
        quality.addAll(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.training.these_are_counters_not_a_verdict_whether_this_session_is_goo")
                        + AeroMessages.tr("gui.training.is_unusable_is_decided_by_the_offline_audit_which_reads_the"), 44));
        inventory.setItem(SLOT_QUALITY, MenuItems.item(
                recording.qualityWarning() ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.training.live_quality"), quality));

        inventory.setItem(SLOT_PROFILE, MenuItems.item(Material.ENDER_EYE,
                MenuItems.HEADER + AeroMessages.tr("gui.training.player_profile"),
                MenuItems.note(AeroMessages.tr("gui.training.what_the_detector_currently_makes_of_them"))));

        inventory.setItem(SLOT_STOP, MenuItems.item(
                confirming ? Material.RED_STAINED_GLASS_PANE : Material.REDSTONE,
                confirming ? MenuItems.BAD + AeroMessages.tr("gui.training.stop_confirm_below") : MenuItems.WARN + AeroMessages.tr("gui.training.stop_recording"),
                MenuItems.note(confirming
                        ? AeroMessages.tr("gui.training.click_the_confirm_button_at_the_bottom")
                        : AeroMessages.tr("gui.training.closes_the_session_and_flushes_it_to_disk"))));

        if (confirming) {
            inventory.setItem(SLOT_CONFIRM, MenuItems.item(Material.RED_CONCRETE,
                    MenuItems.BAD + AeroMessages.tr("gui.training.confirm_stop"),
                    MenuItems.line(AeroMessages.tr("gui.training.player"), recording.playerName()),
                    MenuItems.line(AeroMessages.tr("gui.training.recorded"), AdminStyle.clock(recording.durationSeconds())),
                    "",
                    MenuItems.note(AeroMessages.tr("gui.training.the_data_recorded_so_far_is_kept"))));
        }
        footer(inventory, this::back, AeroMessages.tr("gui.training.active_recordings"));
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
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.training.that_player_is_no_longer_online_the_session_closed_with_them"));
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
                dev.aeroac.neural.NeuralMessages.send(recipient, "The selected session already closed; nothing was stopped.");
                return;
            }
            player.getNeuralState().stop("MANUAL_STOP");
            dev.aeroac.neural.NeuralMessages.send(recipient, "Recording stopped for " + player.getName()
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
