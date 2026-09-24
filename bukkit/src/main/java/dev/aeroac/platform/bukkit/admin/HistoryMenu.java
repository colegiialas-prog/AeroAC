package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminPermissions;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.admin.AdminDetailView;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.risk.EvidenceType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The four history screens behind a profile, which differ only in what they list.
 *
 * <p>All of them read the same bounded ring buffers the engine already keeps. Nothing is stored for
 * the interface, so what fell out of a ring is gone, and each screen says how much it is holding
 * rather than implying it holds everything.
 */
public final class HistoryMenu extends AeroMenu {
    public enum Kind {
        PREDICTIONS("PREDICTIONS"),
        EVIDENCE("EVIDENCE"),
        GRIM_FLAGS("GRIM FLAGS"),
        MITIGATION("MITIGATION HISTORY");

        private final String title;

        Kind(String title) { this.title = title; }
    }

    private final UUID target;
    private final Kind kind;
    private final boolean fromSuspicious;
    private volatile AdminDetailView detail;
    private final java.util.concurrent.atomic.AtomicBoolean loading = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long loadedAt;

    public HistoryMenu(BukkitAdminGui gui, Player viewer, UUID target, Kind kind, boolean fromSuspicious) {
        super(gui, viewer);
        this.target = target;
        this.kind = kind;
        this.fromSuspicious = fromSuspicious;
    }

    @Override protected String title() {
        AdminPlayerView view = service().snapshot().find(target);
        return MenuItems.HEADER + "AERO › " + kind.title + " › " + (view == null ? "?" : view.name());
    }

    @Override public String permission() { return kind == Kind.MITIGATION ? AdminPermissions.MITIGATION : AdminPermissions.PROFILE; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        if (service().snapshot().find(target) == null) {
            detail = null;
            inventory.setItem(22, MenuItems.item(Material.BARRIER, MenuItems.MUTED + AeroMessages.tr("gui.player_offline")));
            footer(inventory, this::back, AeroMessages.tr("gui.the_profile"));
            return;
        }
        requestDetail();
        AdminDetailView loaded = System.currentTimeMillis() - loadedAt <= service().config().refreshMs() * 3L ? detail : null;
        if (loaded == null) {
            inventory.setItem(22, MenuItems.item(Material.CLOCK, MenuItems.MUTED + AeroMessages.tr("gui.loading"),
                    MenuItems.note(AeroMessages.tr("gui.reading_the_history_from_the_player_s_connection"))));
            footer(inventory, this::back, AeroMessages.tr("gui.the_profile"));
            return;
        }
        List<ItemStack> items = switch (kind) {
            case PREDICTIONS -> predictions(loaded);
            case EVIDENCE -> evidence(loaded, false);
            case GRIM_FLAGS -> evidence(loaded, true);
            case MITIGATION -> mitigations(loaded);
        };
        if (items.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    MenuItems.MUTED + AeroMessages.tr("gui.nothing_recorded"), emptyReason()));
        }
        int slot = 0;
        for (ItemStack item : items) {
            if (slot >= 45) break;
            inventory.setItem(slot++, item);
        }
        footer(inventory, this::back, AeroMessages.tr("gui.the_profile"));
    }

    private List<String> emptyReason() {
        return switch (kind) {
            case PREDICTIONS -> MenuItems.wrap(MenuItems.MUTED,
                    AeroMessages.tr("gui.no_prediction_has_come_back_for_this_player_inference_may_be")
                            + AeroMessages.tr("gui.or_no_complete_window_has_been_produced_yet"), 44);
            case EVIDENCE -> MenuItems.wrap(MenuItems.MUTED,
                    AeroMessages.tr("gui.nothing_has_moved_this_player_s_risk_value"), 44);
            case GRIM_FLAGS -> MenuItems.wrap(MenuItems.MUTED,
                    AeroMessages.tr("gui.no_deterministic_check_evidence_has_entered_the_risk_engine")
                            + AeroMessages.tr("gui.checks_still_run_and_still_flag_independently_of_this_screen"), 44);
            case MITIGATION -> MenuItems.wrap(MenuItems.MUTED,
                    AeroMessages.tr("gui.no_mitigation_has_ever_been_applied_to_this_player"), 44);
        };
    }

    private List<ItemStack> predictions(AdminDetailView loaded) {
        List<ItemStack> items = new ArrayList<>();
        List<AdminDetailView.Prediction> rows = loaded.predictions();
        for (int i = rows.size() - 1; i >= 0; i--) {
            AdminDetailView.Prediction row = rows.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(MenuItems.line(AeroMessages.tr("gui.model"), row.model() + " " + row.modelVersion()));
            lore.add(MenuItems.line(AeroMessages.tr("gui.calibrated"), row.calibrated() ? AeroMessages.tr("gui.yes") : AeroMessages.tr("gui.no"),
                    row.calibrated() ? MenuItems.GOOD : MenuItems.WARN));
            lore.add(MenuItems.line(AeroMessages.tr("gui.window"), row.window() == null ? AdminStyle.NO_DATA : row.window()));
            lore.add("");
            lore.add(MenuItems.riskLine(row.overall()));
            for (int head = 0; head < row.headNames().length; head++) {
                if (row.headNames()[head].equals("overall")) continue;
                lore.add(MenuItems.headLine(row.headNames()[head], row.headValues()[head]));
            }
            lore.add("");
            lore.add(MenuItems.line(AeroMessages.tr("gui.latency"), row.latencyMs() + "ms"));
            lore.add(MenuItems.line(AeroMessages.tr("gui.age"), AdminStyle.age(row.ageMs())));
            if (!row.calibrated()) {
                lore.add("");
                lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                        AeroMessages.tr("gui.uncalibrated_this_number_is_a_model_score_not_a_probability"), 44));
            }
            items.add(MenuItems.item(Material.ENDER_EYE,
                    MenuItems.HEADER + "#" + (i + 1) + " " + AdminStyle.percent(row.overall()), lore));
        }
        return items;
    }

    private List<ItemStack> evidence(AdminDetailView loaded, boolean deterministicOnly) {
        List<ItemStack> items = new ArrayList<>();
        if (deterministicOnly) {
            for (EvidenceType type : EvidenceType.values()) {
                if (type.fromModel()) continue;
                long count = loaded.countOf(type);
                if (count == 0) continue;
                items.add(MenuItems.item(Material.COMPARATOR,
                        MenuItems.HEADER + type.name(),
                        MenuItems.line(AeroMessages.tr("gui.accepted_as_evidence"), String.valueOf(count)),
                        "",
                        MenuItems.note(AeroMessages.tr("gui.counted_since_this_connection_began"))));
            }
        }
        List<AdminDetailView.Evidence> rows = loaded.evidence();
        for (int i = rows.size() - 1; i >= 0; i--) {
            AdminDetailView.Evidence row = rows.get(i);
            if (deterministicOnly && row.type().fromModel()) continue;
            List<String> lore = new ArrayList<>();
            lore.add(MenuItems.line(AeroMessages.tr("gui.strength"), AdminStyle.number(row.strength(), 3),
                    row.strength() < 0 ? MenuItems.GOOD : MenuItems.WARN));
            lore.add(MenuItems.line(AeroMessages.tr("gui.source"), AdminStyle.blankIfEmpty(row.source(), AdminStyle.NO_DATA)));
            lore.add(MenuItems.line(AeroMessages.tr("gui.age"), AdminStyle.age(row.ageMs())));
            if (row.metadata() != null && !row.metadata().isBlank()) {
                lore.add("");
                lore.addAll(MenuItems.wrap(MenuItems.MUTED, row.metadata(), 44));
            }
            if (row.strength() < 0) {
                lore.add("");
                lore.add(MenuItems.note(AeroMessages.tr("gui.negative_strength_this_reduced_the_risk")));
            }
            items.add(MenuItems.item(row.type().fromModel() ? Material.ENDER_PEARL : Material.COMPARATOR,
                    (row.type().fromModel() ? MenuItems.WARN : MenuItems.LABEL) + row.type().name(), lore));
        }
        return items;
    }

    private List<ItemStack> mitigations(AdminDetailView loaded) {
        List<ItemStack> items = new ArrayList<>();
        List<AdminDetailView.Mitigation> rows = loaded.mitigations();
        for (int i = rows.size() - 1; i >= 0; i--) {
            AdminDetailView.Mitigation row = rows.get(i);
            items.add(MenuItems.item(Material.IRON_BARS, MenuItems.BAD + row.rule(),
                    MenuItems.line(AeroMessages.tr("gui.risk_at_trigger"), AdminStyle.number(row.riskAtTrigger(), 2)),
                    MenuItems.line(AeroMessages.tr("gui.state_at_trigger"), AdminLabels.state(row.stateAtTrigger()),
                            MenuItems.colourOf(row.stateAtTrigger())),
                    MenuItems.line(AeroMessages.tr("gui.reason"), AdminStyle.blankIfEmpty(row.reason(), AdminStyle.NO_DATA)),
                    MenuItems.line(AeroMessages.tr("gui.duration"), row.durationMs() + "ms"),
                    MenuItems.line(AeroMessages.tr("gui.remaining"), row.remainingMs() > 0 ? row.remainingMs() + "ms" : AeroMessages.tr("gui.expired")),
                    MenuItems.line(AeroMessages.tr("gui.started"), AdminStyle.age(row.ageMs()))));
        }
        return items;
    }

    private void requestDetail() {
        var player = gui.tracked(target);
        if (player == null) { detail = null; return; }
        if (!loading.compareAndSet(false, true)) return;
        service().detail(player, result -> {
            detail = result;
            loadedAt = System.currentTimeMillis();
            loading.set(false);
        });
    }

    @Override public void click(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (isFooterClose(slot, rows())) viewer.closeInventory();
        else if (isFooterBack(slot, rows())) back();
    }

    private void back() {
        gui.show(new ProfileMenu(gui, viewer, target, fromSuspicious));
    }
}
