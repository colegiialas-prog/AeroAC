package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminSnapshot;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.risk.RiskState;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The player list, in two flavours: everybody, or only the players who are flagged.
 *
 * <p>One class for both because they differ by a filter and a title and nothing else, and two
 * copies of this lore would drift apart within a week.
 *
 * <p>Sorted by model output descending, so the row an operator opened this screen to find is the
 * first one. A player the model has never scored is not sorted to the top and is not shown as 0%:
 * the lore says NO DATA, because "the model has not seen enough of this player" and "the model
 * thinks this player is clean" are different answers and only one of them is reassuring.
 */
public final class PlayerListMenu extends AeroMenu {
    private static final int PER_PAGE = 45;

    private final boolean onlySuspicious;
    private int page;
    private final List<UUID> rendered = new ArrayList<>();

    public PlayerListMenu(BukkitAdminGui gui, Player viewer, boolean onlySuspicious, int page) {
        super(gui, viewer);
        this.onlySuspicious = onlySuspicious;
        this.page = page;
    }

    @Override protected String title() {
        return MenuItems.HEADER + "AERO › " + (onlySuspicious ? AeroMessages.tr("gui.suspicious") : AeroMessages.tr("gui.players"));
    }

    @Override public String permission() { return onlySuspicious ? AdminPermissions.SUSPICIOUS : AdminPermissions.PLAYERS; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        AdminSnapshot snapshot = service().snapshot();
        List<AdminPlayerView> source = onlySuspicious ? snapshot.suspicious() : snapshot.players();
        AdminSnapshot.Page<AdminPlayerView> current = AdminSnapshot.page(source, page, PER_PAGE);
        page = current.index();

        rendered.clear();
        int slot = 0;
        for (AdminPlayerView view : current.items()) {
            inventory.setItem(slot++, row(view));
            rendered.add(view.uuid());
        }

        int base = 45;
        inventory.setItem(base + 0, MenuItems.previousPage(current.hasPrevious()));
        inventory.setItem(base + 8, MenuItems.nextPage(current.hasNext()));
        inventory.setItem(base + 4, summary(snapshot, current));
        footer(inventory, this::back, AeroMessages.tr("gui.the_aero_menu"));
    }

    private ItemStack summary(AdminSnapshot snapshot, AdminSnapshot.Page<AdminPlayerView> current) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.showing"), current.total() + (onlySuspicious ? AeroMessages.tr("gui.flagged") : AeroMessages.tr("gui.online_2"))));
        lore.add(MenuItems.line(AeroMessages.tr("gui.page"), current.displayIndex() + " / " + current.pages()));
        lore.add("");
        for (RiskState state : RiskState.values()) {
            lore.add(MenuItems.line(AdminLabels.state(state), String.valueOf(snapshot.count(state)),
                    MenuItems.colourOf(state)));
        }
        return MenuItems.item(Material.PAPER, MenuItems.HEADER
                + (onlySuspicious ? AeroMessages.tr("gui.flagged_players") : AeroMessages.tr("gui.all_players")), lore);
    }

    /** The row lore, in the order an operator reads it: verdict, model, connection, evidence. */
    private ItemStack row(AdminPlayerView view) {
        RiskState state = view.state() == null ? RiskState.CLEAN : view.state();
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(MenuItems.riskLine(view.overall()));
        lore.add(MenuItems.line(AeroMessages.tr("gui.state"), AdminLabels.state(state), MenuItems.colourOf(state)));
        lore.add("");
        lore.add(MenuItems.headLine("Aim Assist", view.aimAssist()));
        lore.add(MenuItems.headLine("KillAura", view.killAura()));
        lore.add(MenuItems.headLine("TriggerBot", view.triggerBot()));
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.ping"), view.ping() < 0 ? AdminStyle.NO_DATA : view.ping() + "ms"));
        lore.add(MenuItems.line(AeroMessages.tr("gui.combat"), AdminStyle.duration(view.combatSeconds())));
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.risk_score"), AdminStyle.number(view.risk(), 2)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.evidence"), String.valueOf(view.evidenceCount())));
        if (view.mitigation() != null) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.mitigation"), view.mitigation() + " "
                    + view.mitigationRemainingMs() + AeroMessages.tr("gui.ms_left"), MenuItems.BAD));
        }
        if (view.isRecording() && permitted(AdminPermissions.TRAINING)) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.recording"), view.recording().shortLabel(),
                    MenuItems.colourOf(view.recording().label())));
        }
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.left_click_profile")));
        lore.add(MenuItems.note(view.watched() ? AeroMessages.tr("gui.right_click_watch_someone_already_is")
                : AeroMessages.tr("gui.right_click_watch")));
        if (permitted(AdminPermissions.ACTION_TELEPORT)) lore.add(MenuItems.note(AeroMessages.tr("gui.action.shift_left_teleport")));
        if (permitted(AdminPermissions.ACTION_SPECTATE)) lore.add(MenuItems.note(AeroMessages.tr("gui.action.shift_right_spectate")));

        String name = MenuItems.colourOf(state) + AdminStyle.symbol(state) + " " + view.name();
        return MenuItems.head(view.uuid(), view.name(), name, lore);
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
        if (slot == 45) {
            if (page > 0) {
                page--;
                redraw();
            }
            return;
        }
        if (slot == 53) {
            page++;
            redraw();
            return;
        }
        if (slot >= rendered.size() || slot >= PER_PAGE) return;
        UUID target = rendered.get(slot);
        if (event.isShiftClick()) {
            // Straight from the list to the suspect: no profile in between when time matters.
            String permission = event.isRightClick() ? AdminPermissions.ACTION_SPECTATE : AdminPermissions.ACTION_TELEPORT;
            org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(target);
            if (!permitted(permission)) deny(permission);
            else if (online == null || online.equals(viewer)) viewer.sendMessage(MenuItems.BAD + AeroMessages.tr("gui.player_offline"));
            else {
                viewer.closeInventory();
                if (event.isRightClick()) gui.actions().toggleSpectate(viewer, online);
                else gui.actions().teleport(viewer, online);
            }
            return;
        }
        if (event.isRightClick()) toggleWatch(target);
        else if (permitted(AdminPermissions.PROFILE)) gui.show(new ProfileMenu(gui, viewer, target, onlySuspicious));
        else deny(AdminPermissions.PROFILE);
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
