package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminPermissions;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminRuntimeControls;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.dataset.DatasetManager;
import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.inference.InferenceHealth;
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
    private static final int SLOT_RUNTIME = 23;

    public StatusMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.aero_neural_status"); }

    @Override public String permission() { return AdminPermissions.STATUS; }

    @Override protected int rows() { return 4; }

    @Override protected void draw(Inventory inventory) {
        NeuralRuntime runtime = gui.neural().runtime();
        if (runtime == null) {
            inventory.setItem(SLOT_RISK, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, MenuItems.MUTED + AeroMessages.tr("gui.telemetry_disabled"),
                    MenuItems.wrap(MenuItems.MUTED,
                             AeroMessages.tr("gui.neural_enabled_is_off_or_nothing_downstream_consumes_frames")
                                     + AeroMessages.tr("gui.deterministic_checks_are_unaffected_and_still_running"), 44)));
            inventory.setItem(SLOT_RUNTIME, runtimeBlock(null));
            footer(inventory, this::back, AeroMessages.tr("gui.the_aero_menu"));
            lockSlot(inventory, SLOT_RUNTIME, AdminPermissions.ADMIN);
            return;
        }
        InferenceHealth health = runtime.health();
        boolean online = runtime.config().inference().enabled() && health != null;

        inventory.setItem(SLOT_INFERENCE, MenuItems.item(
                online ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.inference_2"),
                MenuItems.line(AeroMessages.tr("gui.state"), online ? AeroMessages.tr("gui.online") : AeroMessages.tr("gui.offline"),
                        online ? MenuItems.GOOD : MenuItems.MUTED),
                MenuItems.line(AeroMessages.tr("gui.endpoint"), runtime.config().inference().endpoint()),
                MenuItems.line(AeroMessages.tr("gui.timeout"), runtime.config().inference().timeoutMs() + "ms"),
                MenuItems.line(AeroMessages.tr("gui.in_flight"), runtime.inFlight() < 0 ? AdminStyle.NO_DATA
                        : runtime.inFlight() + " / " + runtime.config().inference().maxInFlight()),
                MenuItems.line(AeroMessages.tr("gui.last_failure"), health == null || health.lastFailure() == null
                        ? AeroMessages.tr("gui.none") : health.lastFailure(),
                        health != null && health.lastFailure() != null ? MenuItems.WARN : MenuItems.GOOD)));

        inventory.setItem(SLOT_MODELS, models(runtime));
        inventory.setItem(SLOT_TRAFFIC, traffic(health));

        inventory.setItem(SLOT_RISK, MenuItems.item(
                runtime.config().risk().enabled() ? Material.OBSERVER : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.risk_engine_2"),
                MenuItems.line(AeroMessages.tr("gui.state"), runtime.config().risk().enabled() ? AeroMessages.tr("gui.on") : AeroMessages.tr("gui.off"),
                        runtime.config().risk().enabled() ? MenuItems.GOOD : MenuItems.MUTED),
                MenuItems.line(AeroMessages.tr("gui.accepts_uncalibrated"), runtime.config().risk().acceptUncalibrated()
                        ? AeroMessages.tr("gui.yes") : AeroMessages.tr("gui.no")),
                MenuItems.line(AeroMessages.tr("gui.state.legend"),
                        AdminStyle.number(runtime.config().risk().watch(), 1) + " / "
                                + AdminStyle.number(runtime.config().risk().suspicious(), 1) + " / "
                                + AdminStyle.number(runtime.config().risk().confirmed(), 1)),
                MenuItems.line(AeroMessages.tr("gui.decay_per_second"),
                        AdminStyle.number(runtime.config().risk().decayPerSecond(), 3))));

        inventory.setItem(SLOT_MITIGATION, MenuItems.item(
                runtime.config().mitigation().enabled() ? Material.IRON_BARS : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.mitigation_2"),
                MenuItems.line(AeroMessages.tr("gui.state"), runtime.config().mitigation().enabled() ? AeroMessages.tr("gui.on") : AeroMessages.tr("gui.off"),
                        runtime.config().mitigation().enabled() ? MenuItems.WARN : MenuItems.MUTED),
                MenuItems.line(AeroMessages.tr("gui.minimum_state"), AdminLabels.state(runtime.config().mitigation().minState())),
                MenuItems.line(AeroMessages.tr("gui.cancels_attacks"), runtime.config().mitigation().cancelAttacks()
                        ? AeroMessages.tr("gui.yes") : AeroMessages.tr("gui.no")),
                MenuItems.line(AeroMessages.tr("gui.duration"), runtime.config().mitigation().durationSeconds() + "s"),
                MenuItems.line(AeroMessages.tr("gui.cap"), runtime.config().mitigation().maxPerHour() + AeroMessages.tr("gui.per_hour"))));

        inventory.setItem(SLOT_RECORDER, recorder());
        inventory.setItem(SLOT_RUNTIME, runtimeBlock(runtime));
        footer(inventory, this::back, AeroMessages.tr("gui.the_aero_menu"));
        lockSlot(inventory, SLOT_RUNTIME, AdminPermissions.ADMIN);
    }

    /**
     * The controlled switch for the runtime itself.
     *
     * <p>This is the one control on this screen that changes what the server does rather than what it
     * shows, so it is never a single click: the button opens a question, and the question is answered
     * once. The switch is only offered when the runtime has published a control; otherwise the item
     * states that a controlled enable is unavailable, which is the honest answer on a server whose
     * runtime is wired at startup.
     *
     * <p>Nothing here starts a runtime or waits for one. The request is handed over and the answer
     * arrives later on the runtime's thread, through the same reply path the confirming screen uses.
     */
    private ItemStack runtimeBlock(NeuralRuntime runtime) {
        boolean enabled = runtime != null;
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.runtime.state"),
                AeroMessages.tr(enabled ? "gui.runtime.state_enabled" : "gui.runtime.state_disabled"),
                enabled ? MenuItems.GOOD : MenuItems.MUTED));
        if (!AdminRuntimeControls.available()) {
            lore.add(MenuItems.note(AeroMessages.tr("gui.runtime.unavailable")));
        } else {
            lore.add(MenuItems.note(AeroMessages.tr(enabled
                    ? "gui.runtime.disable_note" : "gui.runtime.enable_note")));
        }
        String detail = AdminRuntimeControls.stateDetail();
        if (detail != null && !detail.isBlank()) lore.add(MenuItems.note(detail));
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.runtime.async_note")));
        lore.add(MenuItems.note(AeroMessages.tr("gui.requires") + AdminPermissions.ADMIN));
        return MenuItems.item(enabled ? Material.REDSTONE_TORCH : Material.REDSTONE_BLOCK,
                MenuItems.HEADER + AeroMessages.tr(enabled ? "gui.runtime.disable" : "gui.runtime.enable"), lore);
    }

    /** Asks for the runtime to be switched; the click asks, the runtime decides when. */
    private void requestRuntime(boolean enable) {
        if (!permitted(AdminPermissions.ADMIN)) {
            deny(AdminPermissions.ADMIN);
            return;
        }
        if (!AdminRuntimeControls.available()) {
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.runtime.unavailable"));
            return;
        }
        confirm("gui.runtime.title", enable ? "gui.runtime.confirm_enable" : "gui.runtime.confirm_disable",
                new Object[0], enable ? "gui.runtime.enable" : "gui.runtime.disable",
                List.of(MenuItems.note(AeroMessages.tr("gui.runtime.async_note"))),
                () -> {
                    boolean asked = enable
                            ? AdminRuntimeControls.requestEnable(this::reportRuntime)
                            : AdminRuntimeControls.requestDisable(this::reportRuntime);
                    if (!asked) viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.runtime.unavailable"));
                },
                () -> new StatusMenu(gui, viewer));
    }

    /** The runtime's answer, queued back onto the main thread before anything is touched. */
    private void reportRuntime(String message) {
        onMain(() -> {
            if (message != null && !message.isBlank()) {
                viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.runtime.reply") + message);
            }
            if (gui.current(viewer.getUniqueId()) instanceof StatusMenu) gui.show(new StatusMenu(gui, viewer));
        });
    }

    private ItemStack models(NeuralRuntime runtime) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.flash"), runtime.config().inference().enabled() ? AeroMessages.tr("gui.enabled") : AeroMessages.tr("gui.disabled"),
                runtime.config().inference().enabled() ? MenuItems.GOOD : MenuItems.MUTED));
        lore.add(MenuItems.line(AeroMessages.tr("gui.window_label"), runtime.config().inference().flashWindow().wireName()
                + " x" + runtime.config().inference().flashSequence()));
        lore.add(MenuItems.line(AeroMessages.tr("gui.pro"), runtime.config().inference().proEnabled() ? AeroMessages.tr("gui.enabled") : AeroMessages.tr("gui.disabled"),
                runtime.config().inference().proEnabled() ? MenuItems.GOOD : MenuItems.MUTED));
        if (runtime.config().inference().proEnabled()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.window_label"), runtime.config().inference().proWindow().wireName()
                    + " x" + runtime.config().inference().proSequence()));
            lore.add(MenuItems.line(AeroMessages.tr("gui.trigger"),
                    AdminStyle.percent(runtime.config().inference().proTrigger())));
        }
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.feature_schema"), "v" + FeatureEncoder.FEATURE_SCHEMA_VERSION));
        lore.add(MenuItems.line(AeroMessages.tr("gui.model_version"), lastSeenModelVersion()));
        lore.add(MenuItems.note(AeroMessages.tr("gui.the_last_version_any_player_s_prediction_carried")));
        return MenuItems.item(Material.ENDER_EYE, MenuItems.HEADER + AeroMessages.tr("gui.models"), lore);
    }

    private ItemStack traffic(InferenceHealth health) {
        if (health == null) {
            return MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, MenuItems.MUTED + AeroMessages.tr("gui.traffic"),
                    MenuItems.note(AeroMessages.tr("gui.inference_is_disabled_there_is_nothing_to_count")));
        }
        return MenuItems.item(Material.PAPER, MenuItems.HEADER + AeroMessages.tr("gui.traffic"),
                MenuItems.line(AeroMessages.tr("gui.sent"), String.valueOf(health.sentCount())),
                MenuItems.line(AeroMessages.tr("gui.accepted"), String.valueOf(health.acceptedCount()), MenuItems.GOOD),
                MenuItems.line(AeroMessages.tr("gui.timed_out"), String.valueOf(health.timedOutCount()),
                        health.timedOutCount() > 0 ? MenuItems.WARN : MenuItems.VALUE),
                MenuItems.line(AeroMessages.tr("gui.rejected"), String.valueOf(health.rejectedCount()),
                        health.rejectedCount() > 0 ? MenuItems.BAD : MenuItems.VALUE),
                MenuItems.line(AeroMessages.tr("gui.failed"), String.valueOf(health.failedCount()),
                        health.failedCount() > 0 ? MenuItems.BAD : MenuItems.VALUE),
                MenuItems.line(AeroMessages.tr("gui.shed"), String.valueOf(health.shedCount()),
                        health.shedCount() > 0 ? MenuItems.WARN : MenuItems.VALUE),
                "",
                MenuItems.line(AeroMessages.tr("gui.average_latency"), Double.isNaN(health.averageLatencyMs())
                        ? AdminStyle.NO_DATA : AdminStyle.number(health.averageLatencyMs(), 1) + "ms"),
                "",
                MenuItems.note(AeroMessages.tr("gui.a_rejection_is_a_protocol_or_schema_refusal")),
                MenuItems.note(AeroMessages.tr("gui.it_needs_a_redeploy_not_a_retry")));
    }

    private ItemStack recorder() {
        DatasetManager datasets = gui.neural().datasets();
        if (datasets == null) {
            return MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, MenuItems.MUTED + AeroMessages.tr("gui.dataset_recorder"),
                    MenuItems.note(AeroMessages.tr("gui.disk_storage_is_not_open_recording_is_off")));
        }
        return MenuItems.item(Material.WRITABLE_BOOK, MenuItems.HEADER + AeroMessages.tr("gui.dataset_recorder"),
                MenuItems.line(AeroMessages.tr("gui.open_sessions"), String.valueOf(datasets.sessions().size())),
                MenuItems.line(AeroMessages.tr("gui.evidence_snapshots_written"), String.valueOf(datasets.writtenSnapshots())),
                MenuItems.line(AeroMessages.tr("gui.evidence_snapshots_dropped"), String.valueOf(datasets.droppedSnapshots()),
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
        if (isFooterClose(event.getSlot(), rows())) {
            viewer.closeInventory();
            return;
        }
        if (isFooterBack(event.getSlot(), rows())) {
            back();
            return;
        }
        if (event.getSlot() == SLOT_RUNTIME) requestRuntime(gui.neural().runtime() == null);
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
