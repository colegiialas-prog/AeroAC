package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminSnapshot;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.risk.RiskState;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The live board: everyone worth looking at right now, refreshed in place.
 *
 * <p>Players you are watching come first, then everyone flagged, then everyone the model is
 * currently scoring. A player nobody is watching and nothing has flagged is not on this board at
 * all — this is the screen an operator leaves open during a fight, and it is only useful if it
 * stays short.
 */
public final class LiveMonitorMenu extends AeroMenu {
    private static final int PER_PAGE = 45;

    private int page;
    private final List<UUID> rendered = new ArrayList<>();

    public LiveMonitorMenu(BukkitAdminGui gui, Player viewer, int page) {
        super(gui, viewer);
        this.page = page;
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.aero_live_monitor"); }

    @Override public String permission() { return AdminPermissions.MONITOR; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        NeuralRuntime runtime = gui.neural().runtime();
        List<AdminPlayerView> board = new ArrayList<>();
        List<AdminPlayerView> watched = new ArrayList<>();
        for (AdminPlayerView view : service().snapshot().players()) {
            boolean mine = runtime != null && runtime.monitor().watching(view.uuid(), viewer.getUniqueId());
            if (mine) watched.add(view);
            else if (view.suspicious() || view.telemetryActive()) board.add(view);
        }
        watched.addAll(board);
        AdminSnapshot.Page<AdminPlayerView> current = AdminSnapshot.page(watched, page, PER_PAGE);
        page = current.index();

        rendered.clear();
        int slot = 0;
        for (AdminPlayerView view : current.items()) {
            boolean mine = runtime != null && runtime.monitor().watching(view.uuid(), viewer.getUniqueId());
            inventory.setItem(slot++, tile(view, mine));
            rendered.add(view.uuid());
        }
        if (current.total() == 0) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    MenuItems.MUTED + AeroMessages.tr("gui.nobody_in_combat"),
                    MenuItems.note(AeroMessages.tr("gui.telemetry_starts_at_a_player_s_first_attack_and")),
                    MenuItems.note(AeroMessages.tr("gui.stops_again_when_combat_goes_quiet"))));
        }

        inventory.setItem(45, MenuItems.previousPage(current.hasPrevious()));
        inventory.setItem(53, MenuItems.nextPage(current.hasNext()));
        inventory.setItem(49, MenuItems.item(Material.CLOCK, MenuItems.HEADER + AeroMessages.tr("gui.live"),
                MenuItems.line(AeroMessages.tr("gui.refresh"), service().config().refreshMs() + "ms"),
                MenuItems.line(AeroMessages.tr("gui.on_this_board"), String.valueOf(current.total())),
                "",
                MenuItems.note(AeroMessages.tr("gui.right_click_a_head_to_start_or_stop")),
                MenuItems.note(AeroMessages.tr("gui.streaming_their_telemetry_to_your_chat"))));
        footer(inventory, this::back, AeroMessages.tr("gui.the_aero_menu"));
    }

    private org.bukkit.inventory.ItemStack tile(AdminPlayerView view, boolean watchedByMe) {
        RiskState state = view.state() == null ? RiskState.CLEAN : view.state();
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.state"), AdminLabels.state(state), MenuItems.colourOf(state)));
        lore.add(MenuItems.riskLine(view.overall()));
        lore.add(MenuItems.line(AeroMessages.tr("gui.risk"), AdminStyle.number(view.risk(), 2)));
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.target"), view.targetEntityId() < 0 ? AeroMessages.tr("gui.none")
                : String.valueOf(view.targetEntityId())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.aim_error"), AdminStyle.number(view.aimError(), 2)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.delta_yaw_pitch"), AdminStyle.number(view.deltaYaw(), 2)
                + " / " + AdminStyle.number(view.deltaPitch(), 2)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.ping"), view.ping() < 0 ? AdminStyle.NO_DATA : view.ping() + "ms"));
        lore.add(MenuItems.line(AeroMessages.tr("gui.combat"), AdminStyle.duration(view.combatSeconds())));
        if (view.hasPrediction()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.last_prediction"), AdminStyle.age(view.predictionAgeMs())
                    + ", " + view.predictionLatencyMs() + "ms"));
        }
        lore.add("");
        lore.add(watchedByMe ? MenuItems.GOOD + AeroMessages.tr("gui.you_are_watching_this_player")
                : MenuItems.note(AeroMessages.tr("gui.right_click_watch")));
        lore.add(MenuItems.note(AeroMessages.tr("gui.left_click_profile")));
        return MenuItems.head(view.uuid(), view.name(),
                (watchedByMe ? MenuItems.GOOD : MenuItems.colourOf(state))
                        + AdminStyle.symbol(state) + " " + view.name(), lore);
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
        if (slot == 45 && page > 0) {
            page--;
            redraw();
            return;
        }
        if (slot == 53) {
            page++;
            redraw();
            return;
        }
        if (slot >= rendered.size() || slot >= PER_PAGE) return;
        UUID target = rendered.get(slot);
        if (event.isRightClick()) {
            toggleWatch(target);
            redraw();
        } else if (permitted(AdminPermissions.PROFILE)) {
            gui.show(new ProfileMenu(gui, viewer, target, false));
        } else {
            deny(AdminPermissions.PROFILE);
        }
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
