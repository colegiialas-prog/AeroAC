package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminSnapshot;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.admin.training.DatasetSummary;
import dev.aeroac.neural.dataset.DatasetJson;
import dev.aeroac.neural.risk.RiskState;
import dev.aeroac.platform.bukkit.admin.training.TrainingMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * The hub. Ten tiles, each one a place to go, each carrying the number that says whether to go there.
 *
 * <p>Every tile's lore is read from the published snapshot, so opening this screen costs no more
 * than copying a handful of integers.
 */
public final class MainMenu extends AeroMenu {
    private static final int SLOT_PLAYERS = 11;
    private static final int SLOT_SUSPICIOUS = 12;
    private static final int SLOT_MONITOR = 13;
    private static final int SLOT_STATUS = 14;
    private static final int SLOT_CHECKS = 15;
    private static final int SLOT_MITIGATIONS = 20;
    private static final int SLOT_ALERTS = 21;
    private static final int SLOT_STATISTICS = 22;
    private static final int SLOT_TRAINING = 23;
    private static final int SLOT_SETTINGS = 24;
    private static final int SLOT_HEADER = 4;

    public MainMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + "AERO"; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        AdminSnapshot snapshot = service().snapshot();
        NeuralRuntime runtime = gui.neural().runtime();
        DatasetSummary dataset = service().datasetSummary();

        inventory.setItem(SLOT_HEADER, header(snapshot, runtime));

        inventory.setItem(SLOT_PLAYERS, MenuItems.item(Material.PLAYER_HEAD,
                MenuItems.HEADER + AeroMessages.tr("gui.players"),
                MenuItems.line(AeroMessages.tr("gui.online"), String.valueOf(snapshot.players().size())),
                MenuItems.line(AeroMessages.tr("gui.scored_by_the_model"),
                        String.valueOf(countScored(snapshot))),
                "",
                MenuItems.note(AeroMessages.tr("gui.sorted_by_accumulated_risk_highest_first"))));

        int suspicious = snapshot.count(RiskState.WATCH) + snapshot.count(RiskState.SUSPICIOUS)
                + snapshot.count(RiskState.MITIGATED) + snapshot.count(RiskState.CONFIRMED);
        inventory.setItem(SLOT_SUSPICIOUS, MenuItems.item(
                suspicious > 0 ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.suspicious"),
                MenuItems.line(AeroMessages.tr("gui.flagged_now"), String.valueOf(suspicious),
                        suspicious > 0 ? MenuItems.WARN : MenuItems.GOOD),
                stateLine(snapshot, RiskState.WATCH),
                stateLine(snapshot, RiskState.SUSPICIOUS),
                stateLine(snapshot, RiskState.MITIGATED),
                stateLine(snapshot, RiskState.CONFIRMED)));

        inventory.setItem(SLOT_MONITOR, MenuItems.item(Material.ENDER_EYE,
                MenuItems.HEADER + AeroMessages.tr("gui.live_monitor"),
                MenuItems.line(AeroMessages.tr("gui.refresh"), service().config().refreshMs() + "ms"),
                "",
                MenuItems.note(AeroMessages.tr("gui.live_telemetry_for_the_players_you_are_watching"))));

        inventory.setItem(SLOT_STATUS, MenuItems.item(Material.OBSERVER,
                MenuItems.HEADER + AeroMessages.tr("gui.neural_status"),
                MenuItems.line(AeroMessages.tr("gui.telemetry"), runtime == null ? AeroMessages.tr("gui.off") : AeroMessages.tr("gui.on"),
                        runtime == null ? MenuItems.MUTED : MenuItems.GOOD),
                MenuItems.line(AeroMessages.tr("gui.inference"), runtime != null && runtime.config().inference().enabled()
                        ? AeroMessages.tr("gui.on") : AeroMessages.tr("gui.off")),
                MenuItems.line(AeroMessages.tr("gui.risk_engine"), runtime != null && runtime.config().risk().enabled()
                        ? AeroMessages.tr("gui.on") : AeroMessages.tr("gui.off")),
                MenuItems.line(AeroMessages.tr("gui.mitigation"), runtime != null && runtime.config().mitigation().enabled()
                        ? AeroMessages.tr("gui.on") : AeroMessages.tr("gui.off"))));

        inventory.setItem(SLOT_CHECKS, MenuItems.item(Material.COMPARATOR,
                MenuItems.HEADER + AeroMessages.tr("gui.checks"),
                MenuItems.note(AeroMessages.tr("gui.deterministic_check_evidence_kept_separate")),
                MenuItems.note(AeroMessages.tr("gui.from_anything_the_model_produced"))));

        inventory.setItem(SLOT_MITIGATIONS, MenuItems.item(Material.IRON_BARS,
                MenuItems.HEADER + AeroMessages.tr("gui.mitigations"),
                MenuItems.line(AeroMessages.tr("gui.active"), String.valueOf(countMitigated(snapshot))),
                "",
                MenuItems.note(AeroMessages.tr("gui.what_was_acted_on_and_why"))));

        inventory.setItem(SLOT_ALERTS, MenuItems.item(Material.NOTE_BLOCK,
                MenuItems.HEADER + AeroMessages.tr("gui.alerts"),
                MenuItems.line(AeroMessages.tr("gui.yours"), service().alerts().muted(viewer.getUniqueId()) ? AeroMessages.tr("gui.off") : AeroMessages.tr("gui.on"),
                        service().alerts().muted(viewer.getUniqueId()) ? MenuItems.MUTED : MenuItems.GOOD),
                MenuItems.line(AeroMessages.tr("gui.minimum_state"), AdminLabels.state(service().config().alerts().minState())),
                MenuItems.line(AeroMessages.tr("gui.throttle"), service().config().alerts().throttleSeconds() + "s")));

        inventory.setItem(SLOT_STATISTICS, MenuItems.item(Material.BOOK,
                MenuItems.HEADER + AeroMessages.tr("gui.statistics"),
                MenuItems.note(AeroMessages.tr("gui.counters_this_server_has_actually_produced"))));

        inventory.setItem(SLOT_TRAINING, trainingCard(dataset, snapshot));

        inventory.setItem(SLOT_SETTINGS, MenuItems.item(Material.REPEATER,
                MenuItems.HEADER + AeroMessages.tr("gui.settings"),
                MenuItems.line(AeroMessages.tr("gui.your_indicator"), AdminLabels.viewMode(service().viewMode(viewer.getUniqueId()))),
                "",
                MenuItems.note(AeroMessages.tr("gui.interface_settings_only_nothing_here_changes")),
                MenuItems.note(AeroMessages.tr("gui.detection_thresholds_or_mitigation"))));

        footer(inventory, null, null);
        lockSlot(inventory, SLOT_PLAYERS, AdminPermissions.PLAYERS);
        lockSlot(inventory, SLOT_SUSPICIOUS, AdminPermissions.SUSPICIOUS);
        lockSlot(inventory, SLOT_MONITOR, AdminPermissions.MONITOR);
        lockSlot(inventory, SLOT_STATUS, AdminPermissions.STATUS);
        lockSlot(inventory, SLOT_CHECKS, AdminPermissions.STATUS);
        lockSlot(inventory, SLOT_MITIGATIONS, AdminPermissions.MITIGATION);
        lockSlot(inventory, SLOT_ALERTS, AdminPermissions.ALERTS);
        lockSlot(inventory, SLOT_STATISTICS, AdminPermissions.STATUS);
        lockSlot(inventory, SLOT_TRAINING, AdminPermissions.TRAINING);
    }

    private org.bukkit.inventory.ItemStack header(AdminSnapshot snapshot, NeuralRuntime runtime) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.players_online"), String.valueOf(snapshot.players().size())));
        if (permitted(AdminPermissions.STATUS)) lore.add(MenuItems.line(AeroMessages.tr("gui.neural_runtime"), runtime == null ? AeroMessages.tr("gui.disabled") : AeroMessages.tr("gui.generation")
                + runtime.generation(), runtime == null ? MenuItems.MUTED : MenuItems.GOOD));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.percentages_on_these_screens_are_called") + AdminStyle.RISK_LABEL
                        + AeroMessages.tr("gui.they_are_an_experimental_model_output_not_a_measured_probabi")
                        + AeroMessages.tr("gui.somebody_is_cheating"), 44));
        return MenuItems.item(Material.NETHER_STAR, MenuItems.HEADER + AeroMessages.tr("gui.aero_anticheat"), lore);
    }

    /**
     * The training card the operator sees before deciding to open the centre.
     *
     * <p>Attack windows are not on it. Session metadata records frames, not how many attack windows
     * could be built from them, and a number invented here would be the first thing somebody quoted.
     */
    private org.bukkit.inventory.ItemStack trainingCard(DatasetSummary dataset, AdminSnapshot snapshot) {
        List<String> lore = new ArrayList<>();
        int active = snapshot.recordings().size();
        lore.add(MenuItems.line(AeroMessages.tr("gui.active_recordings"), String.valueOf(active),
                active > 0 ? MenuItems.GOOD : MenuItems.VALUE));
        lore.add(MenuItems.line(AeroMessages.tr("gui.dataset_version"), DatasetJson.DATASET_VERSION));
        if (!permitted(AdminPermissions.TRAINING_OVERVIEW)) {
            lore.add(MenuItems.note(AeroMessages.tr("gui.dataset_totals_require") + AdminPermissions.TRAINING_OVERVIEW));
        } else if (!dataset.ready()) {
            lore.add("");
            lore.add(MenuItems.note(dataset.failedToLoad()
                    ? AeroMessages.tr("gui.summary_unavailable") + dataset.error()
                    : AeroMessages.tr("gui.reading_session_metadata_in_the_background")));
        } else {
            lore.add(MenuItems.line(AeroMessages.tr("gui.sessions"), String.valueOf(dataset.sessions())));
            lore.add(MenuItems.line("LEGIT", String.valueOf(dataset.legit()), MenuItems.GOOD));
            lore.add(MenuItems.line("CHEAT", String.valueOf(dataset.cheat()), MenuItems.BAD));
            lore.add(MenuItems.line("UNLABELED", String.valueOf(dataset.unlabeled()), MenuItems.WARN));
            lore.add(MenuItems.line(AeroMessages.tr("gui.attack_windows"), dataset.audit().attackWindows() < 0
                    ? AdminStyle.NO_DATA : String.valueOf(dataset.audit().attackWindows())));
            lore.add(MenuItems.note(AeroMessages.tr("gui.windows_are_counted_by_the_offline_audit")));
        }
        return MenuItems.item(Material.WRITABLE_BOOK, MenuItems.HEADER + AeroMessages.tr("gui.training_center"), lore);
    }

    private String stateLine(AdminSnapshot snapshot, RiskState state) {
        return MenuItems.line(AdminLabels.state(state), String.valueOf(snapshot.count(state)), MenuItems.colourOf(state));
    }

    private static int countScored(AdminSnapshot snapshot) {
        int scored = 0;
        for (var view : snapshot.players()) if (view.hasPrediction()) scored++;
        return scored;
    }

    private static int countMitigated(AdminSnapshot snapshot) {
        int active = 0;
        for (var view : snapshot.players()) if (view.mitigation() != null) active++;
        return active;
    }

    @Override public void click(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (isFooterClose(slot, rows())) {
            viewer.closeInventory();
            return;
        }
        switch (slot) {
            case SLOT_PLAYERS -> require(AdminPermissions.PLAYERS,
                    () -> gui.show(new PlayerListMenu(gui, viewer, false, 0)));
            case SLOT_SUSPICIOUS -> require(AdminPermissions.SUSPICIOUS,
                    () -> gui.show(new PlayerListMenu(gui, viewer, true, 0)));
            case SLOT_MONITOR -> require(AdminPermissions.MONITOR,
                    () -> gui.show(new LiveMonitorMenu(gui, viewer, 0)));
            case SLOT_STATUS -> require(AdminPermissions.STATUS,
                    () -> gui.show(new StatusMenu(gui, viewer)));
            case SLOT_CHECKS -> require(AdminPermissions.STATUS,
                    () -> gui.show(new ChecksMenu(gui, viewer)));
            case SLOT_MITIGATIONS -> require(AdminPermissions.MITIGATION,
                    () -> gui.show(new MitigationsMenu(gui, viewer, 0)));
            case SLOT_ALERTS -> require(AdminPermissions.ALERTS,
                    () -> gui.show(new AlertsMenu(gui, viewer)));
            case SLOT_STATISTICS -> require(AdminPermissions.STATUS,
                    () -> gui.show(new StatisticsMenu(gui, viewer)));
            case SLOT_TRAINING -> require(AdminPermissions.TRAINING,
                    () -> gui.show(new TrainingMenu(gui, viewer)));
            case SLOT_SETTINGS -> require(AdminPermissions.GUI,
                    () -> gui.show(new SettingsMenu(gui, viewer)));
            default -> { }
        }
    }

    private void require(String permission, Runnable action) {
        if (permitted(permission)) action.run();
        else deny(permission);
    }
}
