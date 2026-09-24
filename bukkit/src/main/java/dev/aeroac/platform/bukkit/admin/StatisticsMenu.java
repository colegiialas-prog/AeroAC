package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminPermissions;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminSnapshot;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.admin.training.DatasetSummary;
import dev.aeroac.neural.inference.InferenceHealth;
import dev.aeroac.neural.risk.EvidenceType;
import dev.aeroac.neural.risk.RiskState;
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

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.aero_statistics"); }

    @Override public String permission() { return AdminPermissions.STATUS; }

    @Override protected int rows() { return 5; }

    @Override protected void draw(Inventory inventory) {
        AdminSnapshot snapshot = service().snapshot();
        NeuralRuntime runtime = gui.neural().runtime();
        DatasetSummary dataset = service().datasetSummary();

        List<String> states = new ArrayList<>();
        for (RiskState state : RiskState.values()) {
            states.add(MenuItems.line(AdminLabels.state(state), String.valueOf(snapshot.count(state)),
                    MenuItems.colourOf(state)));
        }
        states.add("");
        states.add(MenuItems.line(AeroMessages.tr("gui.players_online"), String.valueOf(snapshot.players().size())));
        states.add(MenuItems.line(AeroMessages.tr("gui.scored_by_the_model"), String.valueOf(scored(snapshot))));
        states.add(MenuItems.line(AeroMessages.tr("gui.in_combat_now"), String.valueOf(inCombat(snapshot))));
        inventory.setItem(11, MenuItems.item(Material.PLAYER_HEAD, MenuItems.HEADER + AeroMessages.tr("gui.right_now"), states));

        InferenceHealth health = runtime == null ? null : runtime.health();
        inventory.setItem(12, health == null
                ? MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, MenuItems.MUTED + AeroMessages.tr("gui.inference_2"),
                        MenuItems.note(AeroMessages.tr("gui.disabled_no_traffic_has_been_counted")))
                : MenuItems.item(Material.ENDER_EYE, MenuItems.HEADER + AeroMessages.tr("gui.inference_2"),
                        MenuItems.line(AeroMessages.tr("gui.sent"), String.valueOf(health.sentCount())),
                        MenuItems.line(AeroMessages.tr("gui.accepted"), String.valueOf(health.acceptedCount())),
                        MenuItems.line(AeroMessages.tr("gui.lost_to_timeout"), String.valueOf(health.timedOutCount())),
                        MenuItems.line(AeroMessages.tr("gui.refused"), String.valueOf(health.rejectedCount() + health.failedCount())),
                        MenuItems.line(AeroMessages.tr("gui.shed"), String.valueOf(health.shedCount())),
                        MenuItems.line(AeroMessages.tr("gui.average_latency"), Double.isNaN(health.averageLatencyMs())
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
        evidence.add(MenuItems.line(AeroMessages.tr("gui.from_the_model"), String.valueOf(model)));
        evidence.add(MenuItems.line(AeroMessages.tr("gui.from_deterministic_checks"), String.valueOf(deterministic)));
        evidence.add("");
        evidence.addAll(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.counted_for_players_online_now_since_each_connection_began"), 44));
        inventory.setItem(13, MenuItems.item(Material.BOOK, MenuItems.HEADER + AeroMessages.tr("gui.evidence_accepted"), evidence));

        inventory.setItem(14, MenuItems.item(Material.WRITABLE_BOOK, MenuItems.HEADER + AeroMessages.tr("gui.dataset"),
                dataset.ready()
                        ? List.of(MenuItems.line(AeroMessages.tr("gui.sessions"), String.valueOf(dataset.sessions())),
                                MenuItems.line(AeroMessages.tr("gui.players_2"), String.valueOf(dataset.players())),
                                MenuItems.line(AeroMessages.tr("gui.recorded_combat"),
                                        AdminStyle.duration(dataset.totalDurationSeconds())),
                                MenuItems.line(AeroMessages.tr("gui.frames"), String.valueOf(dataset.totalFrames())),
                                MenuItems.line(AeroMessages.tr("gui.human_reviewed"), String.valueOf(dataset.reviewed())))
                        : List.of(MenuItems.note(dataset.failedToLoad()
                                ? AeroMessages.tr("gui.unavailable") + dataset.error() : AeroMessages.tr("gui.loading_in_the_background")))));

        List<String> caveat = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.no_detection_rate_and_no_false_positive_rate_appear_on_this")
                        + AeroMessages.tr("gui.measured_by_evaluating_a_model_against_reviewed_sessions_whi")
                        + AeroMessages.tr("gui.the_python_tooling_not_on_a_running_server"), 44));
        inventory.setItem(31, MenuItems.item(Material.PAPER, MenuItems.HEADER + AeroMessages.tr("gui.what_is_not_here"), caveat));

        footer(inventory, this::back, AeroMessages.tr("gui.the_aero_menu"));
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
