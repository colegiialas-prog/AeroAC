package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.neural.admin.AdminPermissions;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.NeuralRuntime;
import ac.grim.grimac.neural.admin.AdminPlayerView;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.dataset.DatasetManager;
import ac.grim.grimac.neural.inference.FeatureEncoder;
import ac.grim.grimac.neural.inference.InferenceHealth;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Service health: what is switched on, what the inference client has actually done, and what the
 * recorder is holding.
 *
 * <p>Every number is a counter the runtime was already keeping. Opening this screen sends no
 * request, opens no connection and asks the service nothing, which is the only way a status screen
 * can be safe to leave open on a busy server.
 */
public final class StatusMenu extends AeroMenu {
    private static final int SLOT_INFERENCE = 11;
    private static final int SLOT_MODELS = 12;
    private static final int SLOT_TRAFFIC = 13;
    private static final int SLOT_RISK = 14;
    private static final int SLOT_MITIGATION = 15;
    private static final int SLOT_RECORDER = 22;

    public StatusMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + "AERO › NEURAL STATUS"; }

    @Override public String permission() { return AdminPermissions.STATUS; }

    @Override protected int rows() { return 4; }

    @Override protected void draw(Inventory inventory) {
        NeuralRuntime runtime = gui.neural().runtime();
        if (runtime == null) {
            inventory.setItem(SLOT_RISK, MenuItems.item(Material.GRAY_DYE, MenuItems.MUTED + "TELEMETRY DISABLED",
                    MenuItems.wrap(MenuItems.MUTED,
                            "neural.enabled is off, or nothing downstream consumes frames. "
                                    + "Deterministic checks are unaffected and still running.", 44)));
            footer(inventory, this::back, "the Aero menu");
            return;
        }
        InferenceHealth health = runtime.health();
        boolean online = runtime.config().inference().enabled() && health != null;

        inventory.setItem(SLOT_INFERENCE, MenuItems.item(
                online ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + "INFERENCE",
                MenuItems.line("State", online ? "online" : "offline",
                        online ? MenuItems.GOOD : MenuItems.MUTED),
                MenuItems.line("Endpoint", runtime.config().inference().endpoint()),
                MenuItems.line("Timeout", runtime.config().inference().timeoutMs() + "ms"),
                MenuItems.line("In flight", runtime.inFlight() < 0 ? AdminStyle.NO_DATA
                        : runtime.inFlight() + " / " + runtime.config().inference().maxInFlight()),
                MenuItems.line("Last failure", health == null || health.lastFailure() == null
                        ? "none" : health.lastFailure(),
                        health != null && health.lastFailure() != null ? MenuItems.WARN : MenuItems.GOOD)));

        inventory.setItem(SLOT_MODELS, models(runtime));
        inventory.setItem(SLOT_TRAFFIC, traffic(health));

        inventory.setItem(SLOT_RISK, MenuItems.item(
                runtime.config().risk().enabled() ? Material.OBSERVER : Material.GRAY_DYE,
                MenuItems.HEADER + "RISK ENGINE",
                MenuItems.line("State", runtime.config().risk().enabled() ? "on" : "off",
                        runtime.config().risk().enabled() ? MenuItems.GOOD : MenuItems.MUTED),
                MenuItems.line("Accepts uncalibrated", runtime.config().risk().acceptUncalibrated()
                        ? "yes" : "no"),
                MenuItems.line("Watch / Suspicious / Confirmed",
                        AdminStyle.number(runtime.config().risk().watch(), 1) + " / "
                                + AdminStyle.number(runtime.config().risk().suspicious(), 1) + " / "
                                + AdminStyle.number(runtime.config().risk().confirmed(), 1)),
                MenuItems.line("Decay per second",
                        AdminStyle.number(runtime.config().risk().decayPerSecond(), 3))));

        inventory.setItem(SLOT_MITIGATION, MenuItems.item(
                runtime.config().mitigation().enabled() ? Material.IRON_BARS : Material.GRAY_DYE,
                MenuItems.HEADER + "MITIGATION",
                MenuItems.line("State", runtime.config().mitigation().enabled() ? "on" : "off",
                        runtime.config().mitigation().enabled() ? MenuItems.WARN : MenuItems.MUTED),
                MenuItems.line("Minimum state", runtime.config().mitigation().minState().name()),
                MenuItems.line("Cancels attacks", runtime.config().mitigation().cancelAttacks()
                        ? "yes" : "no"),
                MenuItems.line("Duration", runtime.config().mitigation().durationSeconds() + "s"),
                MenuItems.line("Cap", runtime.config().mitigation().maxPerHour() + " per hour")));

        inventory.setItem(SLOT_RECORDER, recorder());
        footer(inventory, this::back, "the Aero menu");
    }

    private ItemStack models(NeuralRuntime runtime) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line("Flash", runtime.config().inference().enabled() ? "enabled" : "disabled",
                runtime.config().inference().enabled() ? MenuItems.GOOD : MenuItems.MUTED));
        lore.add(MenuItems.line("  window", runtime.config().inference().flashWindow().wireName()
                + " x" + runtime.config().inference().flashSequence()));
        lore.add(MenuItems.line("Pro", runtime.config().inference().proEnabled() ? "enabled" : "disabled",
                runtime.config().inference().proEnabled() ? MenuItems.GOOD : MenuItems.MUTED));
        if (runtime.config().inference().proEnabled()) {
            lore.add(MenuItems.line("  window", runtime.config().inference().proWindow().wireName()
                    + " x" + runtime.config().inference().proSequence()));
            lore.add(MenuItems.line("  trigger",
                    AdminStyle.percent(runtime.config().inference().proTrigger())));
        }
        lore.add("");
        lore.add(MenuItems.line("Feature schema", "v" + FeatureEncoder.FEATURE_SCHEMA_VERSION));
        lore.add(MenuItems.line("Model version", lastSeenModelVersion()));
        lore.add(MenuItems.note("  the last version any player's prediction carried"));
        return MenuItems.item(Material.ENDER_EYE, MenuItems.HEADER + "MODELS", lore);
    }

    private ItemStack traffic(InferenceHealth health) {
        if (health == null) {
            return MenuItems.item(Material.GRAY_DYE, MenuItems.MUTED + "TRAFFIC",
                    MenuItems.note("Inference is disabled; there is nothing to count."));
        }
        return MenuItems.item(Material.PAPER, MenuItems.HEADER + "TRAFFIC",
                MenuItems.line("Sent", String.valueOf(health.sentCount())),
                MenuItems.line("Accepted", String.valueOf(health.acceptedCount()), MenuItems.GOOD),
                MenuItems.line("Timed out", String.valueOf(health.timedOutCount()),
                        health.timedOutCount() > 0 ? MenuItems.WARN : MenuItems.VALUE),
                MenuItems.line("Rejected", String.valueOf(health.rejectedCount()),
                        health.rejectedCount() > 0 ? MenuItems.BAD : MenuItems.VALUE),
                MenuItems.line("Failed", String.valueOf(health.failedCount()),
                        health.failedCount() > 0 ? MenuItems.BAD : MenuItems.VALUE),
                MenuItems.line("Shed", String.valueOf(health.shedCount()),
                        health.shedCount() > 0 ? MenuItems.WARN : MenuItems.VALUE),
                "",
                MenuItems.line("Average latency", Double.isNaN(health.averageLatencyMs())
                        ? AdminStyle.NO_DATA : AdminStyle.number(health.averageLatencyMs(), 1) + "ms"),
                "",
                MenuItems.note("A rejection is a protocol or schema refusal:"),
                MenuItems.note("it needs a redeploy, not a retry."));
    }

    private ItemStack recorder() {
        DatasetManager datasets = gui.neural().datasets();
        if (datasets == null) {
            return MenuItems.item(Material.GRAY_DYE, MenuItems.MUTED + "DATASET RECORDER",
                    MenuItems.note("Disk storage is not open; recording is off."));
        }
        return MenuItems.item(Material.WRITABLE_BOOK, MenuItems.HEADER + "DATASET RECORDER",
                MenuItems.line("Open sessions", String.valueOf(datasets.sessions().size())),
                MenuItems.line("Evidence snapshots written", String.valueOf(datasets.writtenSnapshots())),
                MenuItems.line("Evidence snapshots dropped", String.valueOf(datasets.droppedSnapshots()),
                        datasets.droppedSnapshots() > 0 ? MenuItems.WARN : MenuItems.VALUE));
    }

    /** The model version last seen on the wire, which is the only one this server can vouch for. */
    private String lastSeenModelVersion() {
        long newest = Long.MAX_VALUE;
        String version = null;
        for (AdminPlayerView view : service().snapshot().players()) {
            if (view.modelVersion() == null || view.predictionAgeMs() < 0) continue;
            if (view.predictionAgeMs() < newest) {
                newest = view.predictionAgeMs();
                version = view.modelVersion();
            }
        }
        return version == null ? AdminStyle.NO_DATA : version;
    }

    @Override public void click(InventoryClickEvent event) {
        if (isFooterClose(event.getSlot(), rows())) viewer.closeInventory();
        else if (isFooterBack(event.getSlot(), rows())) back();
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
