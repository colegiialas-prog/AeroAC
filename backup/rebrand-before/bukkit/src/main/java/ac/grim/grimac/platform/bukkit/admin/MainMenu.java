package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.NeuralRuntime;
import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminSnapshot;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.admin.training.DatasetSummary;
import ac.grim.grimac.neural.dataset.DatasetJson;
import ac.grim.grimac.neural.risk.RiskState;
import ac.grim.grimac.platform.bukkit.admin.training.TrainingMenu;
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
                MenuItems.HEADER + "PLAYERS",
                MenuItems.line("Online", String.valueOf(snapshot.players().size())),
                MenuItems.line("Scored by the model",
                        String.valueOf(countScored(snapshot))),
                "",
                MenuItems.note("Sorted by accumulated risk, highest first.")));

        int suspicious = snapshot.count(RiskState.WATCH) + snapshot.count(RiskState.SUSPICIOUS)
                + snapshot.count(RiskState.MITIGATED) + snapshot.count(RiskState.CONFIRMED);
        inventory.setItem(SLOT_SUSPICIOUS, MenuItems.item(
                suspicious > 0 ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                MenuItems.HEADER + "SUSPICIOUS",
                MenuItems.line("Flagged now", String.valueOf(suspicious),
                        suspicious > 0 ? MenuItems.WARN : MenuItems.GOOD),
                stateLine(snapshot, RiskState.WATCH),
                stateLine(snapshot, RiskState.SUSPICIOUS),
                stateLine(snapshot, RiskState.MITIGATED),
                stateLine(snapshot, RiskState.CONFIRMED)));

        inventory.setItem(SLOT_MONITOR, MenuItems.item(Material.ENDER_EYE,
                MenuItems.HEADER + "LIVE MONITOR",
                MenuItems.line("Refresh", service().config().refreshMs() + "ms"),
                "",
                MenuItems.note("Live telemetry for the players you are watching.")));

        inventory.setItem(SLOT_STATUS, MenuItems.item(Material.OBSERVER,
                MenuItems.HEADER + "NEURAL STATUS",
                MenuItems.line("Telemetry", runtime == null ? "off" : "on",
                        runtime == null ? MenuItems.MUTED : MenuItems.GOOD),
                MenuItems.line("Inference", runtime != null && runtime.config().inference().enabled()
                        ? "on" : "off"),
                MenuItems.line("Risk engine", runtime != null && runtime.config().risk().enabled()
                        ? "on" : "off"),
                MenuItems.line("Mitigation", runtime != null && runtime.config().mitigation().enabled()
                        ? "on" : "off")));

        inventory.setItem(SLOT_CHECKS, MenuItems.item(Material.COMPARATOR,
                MenuItems.HEADER + "CHECKS",
                MenuItems.note("Deterministic check evidence, kept separate"),
                MenuItems.note("from anything the model produced.")));

        inventory.setItem(SLOT_MITIGATIONS, MenuItems.item(Material.IRON_BARS,
                MenuItems.HEADER + "MITIGATIONS",
                MenuItems.line("Active", String.valueOf(countMitigated(snapshot))),
                "",
                MenuItems.note("What was acted on, and why.")));

        inventory.setItem(SLOT_ALERTS, MenuItems.item(Material.BELL,
                MenuItems.HEADER + "ALERTS",
                MenuItems.line("Yours", service().alerts().muted(viewer.getUniqueId()) ? "off" : "on",
                        service().alerts().muted(viewer.getUniqueId()) ? MenuItems.MUTED : MenuItems.GOOD),
                MenuItems.line("Minimum state", service().config().alerts().minState().name()),
                MenuItems.line("Throttle", service().config().alerts().throttleSeconds() + "s")));

        inventory.setItem(SLOT_STATISTICS, MenuItems.item(Material.BOOK,
                MenuItems.HEADER + "STATISTICS",
                MenuItems.note("Counters this server has actually produced.")));

        inventory.setItem(SLOT_TRAINING, trainingCard(dataset, snapshot));

        inventory.setItem(SLOT_SETTINGS, MenuItems.item(Material.REPEATER,
                MenuItems.HEADER + "SETTINGS",
                MenuItems.line("Your indicator", service().viewMode(viewer.getUniqueId()).name()),
                "",
                MenuItems.note("Interface settings only. Nothing here changes"),
                MenuItems.note("detection, thresholds or mitigation.")));

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
        lore.add(MenuItems.line("Players online", String.valueOf(snapshot.players().size())));
        if (permitted(AdminPermissions.STATUS)) lore.add(MenuItems.line("Neural runtime", runtime == null ? "disabled" : "generation "
                + runtime.generation(), runtime == null ? MenuItems.MUTED : MenuItems.GOOD));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                "Percentages on these screens are called " + AdminStyle.RISK_LABEL
                        + ". They are an experimental model output, not a measured probability that "
                        + "somebody is cheating.", 44));
        return MenuItems.item(Material.NETHER_STAR, MenuItems.HEADER + "AERO ANTICHEAT", lore);
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
        lore.add(MenuItems.line("Active recordings", String.valueOf(active),
                active > 0 ? MenuItems.GOOD : MenuItems.VALUE));
        lore.add(MenuItems.line("Dataset version", DatasetJson.DATASET_VERSION));
        if (!permitted(AdminPermissions.TRAINING_OVERVIEW)) {
            lore.add(MenuItems.note("Dataset totals require " + AdminPermissions.TRAINING_OVERVIEW));
        } else if (!dataset.ready()) {
            lore.add("");
            lore.add(MenuItems.note(dataset.failedToLoad()
                    ? "Summary unavailable: " + dataset.error()
                    : "Reading session metadata in the background..."));
        } else {
            lore.add(MenuItems.line("Sessions", String.valueOf(dataset.sessions())));
            lore.add(MenuItems.line("LEGIT", String.valueOf(dataset.legit()), MenuItems.GOOD));
            lore.add(MenuItems.line("CHEAT", String.valueOf(dataset.cheat()), MenuItems.BAD));
            lore.add(MenuItems.line("UNLABELED", String.valueOf(dataset.unlabeled()), MenuItems.WARN));
            lore.add(MenuItems.line("Attack windows", dataset.audit().attackWindows() < 0
                    ? AdminStyle.NO_DATA : String.valueOf(dataset.audit().attackWindows())));
            lore.add(MenuItems.note("  windows are counted by the offline audit"));
        }
        return MenuItems.item(Material.WRITABLE_BOOK, MenuItems.HEADER + "TRAINING CENTER", lore);
    }

    private String stateLine(AdminSnapshot snapshot, RiskState state) {
        return MenuItems.line(state.name(), String.valueOf(snapshot.count(state)), MenuItems.colourOf(state));
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
