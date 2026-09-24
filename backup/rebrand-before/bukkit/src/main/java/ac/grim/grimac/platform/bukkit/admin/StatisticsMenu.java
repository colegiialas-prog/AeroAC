package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.neural.admin.AdminPermissions;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.NeuralRuntime;
import ac.grim.grimac.neural.admin.AdminPlayerView;
import ac.grim.grimac.neural.admin.AdminSnapshot;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.admin.training.DatasetSummary;
import ac.grim.grimac.neural.inference.InferenceHealth;
import ac.grim.grimac.neural.risk.EvidenceType;
import ac.grim.grimac.neural.risk.RiskState;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Counters this server has actually produced, and nothing that would need a model to be trusted.
 *
 * <p>There is no detection rate on this screen and no false-positive rate. Both are properties of a
 * model measured against reviewed data, not of a running server, and printing a plausible-looking
 * number here would be the fastest possible way to have the anticheat's accuracy quoted from a
 * screen that never measured it.
 */
public final class StatisticsMenu extends AeroMenu {
    public StatisticsMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + "AERO › STATISTICS"; }

    @Override public String permission() { return AdminPermissions.STATUS; }

    @Override protected int rows() { return 5; }

    @Override protected void draw(Inventory inventory) {
        AdminSnapshot snapshot = service().snapshot();
        NeuralRuntime runtime = gui.neural().runtime();
        DatasetSummary dataset = service().datasetSummary();

        List<String> states = new ArrayList<>();
        for (RiskState state : RiskState.values()) {
            states.add(MenuItems.line(state.name(), String.valueOf(snapshot.count(state)),
                    MenuItems.colourOf(state)));
        }
        states.add("");
        states.add(MenuItems.line("Players online", String.valueOf(snapshot.players().size())));
        states.add(MenuItems.line("Scored by the model", String.valueOf(scored(snapshot))));
        states.add(MenuItems.line("In combat now", String.valueOf(inCombat(snapshot))));
        inventory.setItem(11, MenuItems.item(Material.PLAYER_HEAD, MenuItems.HEADER + "RIGHT NOW", states));

        InferenceHealth health = runtime == null ? null : runtime.health();
        inventory.setItem(12, health == null
                ? MenuItems.item(Material.GRAY_DYE, MenuItems.MUTED + "INFERENCE",
                        MenuItems.note("Disabled; no traffic has been counted."))
                : MenuItems.item(Material.ENDER_EYE, MenuItems.HEADER + "INFERENCE",
                        MenuItems.line("Sent", String.valueOf(health.sentCount())),
                        MenuItems.line("Accepted", String.valueOf(health.acceptedCount())),
                        MenuItems.line("Lost to timeout", String.valueOf(health.timedOutCount())),
                        MenuItems.line("Refused", String.valueOf(health.rejectedCount() + health.failedCount())),
                        MenuItems.line("Shed", String.valueOf(health.shedCount())),
                        MenuItems.line("Average latency", Double.isNaN(health.averageLatencyMs())
                                ? AdminStyle.NO_DATA : AdminStyle.number(health.averageLatencyMs(), 1) + "ms")));

        List<String> evidence = new ArrayList<>();
        long model = 0;
        long deterministic = 0;
        for (AdminPlayerView view : snapshot.players()) {
            for (EvidenceType type : EvidenceType.values()) {
                if (type.fromModel()) model += view.evidenceOf(type);
                else deterministic += view.evidenceOf(type);
            }
        }
        evidence.add(MenuItems.line("From the model", String.valueOf(model)));
        evidence.add(MenuItems.line("From deterministic checks", String.valueOf(deterministic)));
        evidence.add("");
        evidence.addAll(MenuItems.wrap(MenuItems.MUTED,
                "Counted for players online now, since each connection began.", 44));
        inventory.setItem(13, MenuItems.item(Material.BOOK, MenuItems.HEADER + "EVIDENCE ACCEPTED", evidence));

        inventory.setItem(14, MenuItems.item(Material.WRITABLE_BOOK, MenuItems.HEADER + "DATASET",
                dataset.ready()
                        ? List.of(MenuItems.line("Sessions", String.valueOf(dataset.sessions())),
                                MenuItems.line("Players", String.valueOf(dataset.players())),
                                MenuItems.line("Recorded combat",
                                        AdminStyle.duration(dataset.totalDurationSeconds())),
                                MenuItems.line("Frames", String.valueOf(dataset.totalFrames())),
                                MenuItems.line("Human reviewed", String.valueOf(dataset.reviewed())))
                        : List.of(MenuItems.note(dataset.failedToLoad()
                                ? "Unavailable: " + dataset.error() : "Loading in the background..."))));

        List<String> caveat = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED,
                "No detection rate and no false-positive rate appear on this screen. Those are "
                        + "measured by evaluating a model against reviewed sessions, which happens in "
                        + "the Python tooling, not on a running server.", 44));
        inventory.setItem(31, MenuItems.item(Material.PAPER, MenuItems.HEADER + "WHAT IS NOT HERE", caveat));

        footer(inventory, this::back, "the Aero menu");
    }

    private static int scored(AdminSnapshot snapshot) {
        int count = 0;
        for (AdminPlayerView view : snapshot.players()) if (view.hasPrediction()) count++;
        return count;
    }

    private static int inCombat(AdminSnapshot snapshot) {
        int count = 0;
        for (AdminPlayerView view : snapshot.players()) if (view.telemetryActive()) count++;
        return count;
    }

    @Override public void click(InventoryClickEvent event) {
        if (isFooterClose(event.getSlot(), rows())) viewer.closeInventory();
        else if (isFooterBack(event.getSlot(), rows())) back();
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
