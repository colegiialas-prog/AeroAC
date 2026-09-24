package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.NeuralRuntime;
import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminPlayerView;
import ac.grim.grimac.neural.admin.AdminSnapshot;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.risk.RiskState;
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

    @Override protected String title() { return MenuItems.HEADER + "AERO › LIVE MONITOR"; }

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
                    MenuItems.MUTED + "NOBODY IN COMBAT",
                    MenuItems.note("Telemetry starts at a player's first attack and"),
                    MenuItems.note("stops again when combat goes quiet.")));
        }

        inventory.setItem(45, MenuItems.previousPage(current.hasPrevious()));
        inventory.setItem(53, MenuItems.nextPage(current.hasNext()));
        inventory.setItem(49, MenuItems.item(Material.CLOCK, MenuItems.HEADER + "LIVE",
                MenuItems.line("Refresh", service().config().refreshMs() + "ms"),
                MenuItems.line("On this board", String.valueOf(current.total())),
                "",
                MenuItems.note("Right click a head to start or stop"),
                MenuItems.note("streaming their telemetry to your chat.")));
        footer(inventory, this::back, "the Aero menu");
    }

    private org.bukkit.inventory.ItemStack tile(AdminPlayerView view, boolean watchedByMe) {
        RiskState state = view.state() == null ? RiskState.CLEAN : view.state();
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line("State", state.name(), MenuItems.colourOf(state)));
        lore.add(MenuItems.riskLine(view.overall()));
        lore.add(MenuItems.line("Risk", AdminStyle.number(view.risk(), 2)));
        lore.add("");
        lore.add(MenuItems.line("Target", view.targetEntityId() < 0 ? "none"
                : String.valueOf(view.targetEntityId())));
        lore.add(MenuItems.line("Aim error", AdminStyle.number(view.aimError(), 2)));
        lore.add(MenuItems.line("Delta yaw/pitch", AdminStyle.number(view.deltaYaw(), 2)
                + " / " + AdminStyle.number(view.deltaPitch(), 2)));
        lore.add(MenuItems.line("Ping", view.ping() < 0 ? AdminStyle.NO_DATA : view.ping() + "ms"));
        lore.add(MenuItems.line("Combat", AdminStyle.duration(view.combatSeconds())));
        if (view.hasPrediction()) {
            lore.add(MenuItems.line("Last prediction", AdminStyle.age(view.predictionAgeMs())
                    + ", " + view.predictionLatencyMs() + "ms"));
        }
        lore.add("");
        lore.add(watchedByMe ? MenuItems.GOOD + "You are watching this player"
                : MenuItems.note("Right click: watch"));
        lore.add(MenuItems.note("Left click: profile"));
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
