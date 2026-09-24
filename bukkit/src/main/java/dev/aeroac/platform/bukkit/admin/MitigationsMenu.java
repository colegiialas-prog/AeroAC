package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminSnapshot;
import dev.aeroac.neural.admin.AdminStyle;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who has been acted on, with what, and on what evidence.
 *
 * <p>Read-only. There is no button here that starts or stops a mitigation: mitigation is decided by
 * the rules an operator configured, and an interface that let one be applied by hand would be a
 * punishment tool wearing a monitoring tool's clothes.
 */
public final class MitigationsMenu extends AeroMenu {
    private static final int PER_PAGE = 45;

    private int page;
    private final List<UUID> rendered = new ArrayList<>();

    public MitigationsMenu(BukkitAdminGui gui, Player viewer, int page) {
        super(gui, viewer);
        this.page = page;
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.aero_mitigations"); }

    @Override public String permission() { return AdminPermissions.MITIGATION; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        NeuralRuntime runtime = gui.neural().runtime();
        List<AdminPlayerView> touched = new ArrayList<>();
        for (AdminPlayerView view : service().snapshot().players()) {
            if (view.mitigation() != null || view.mitigationCount() > 0) touched.add(view);
        }
        AdminSnapshot.Page<AdminPlayerView> current = AdminSnapshot.page(touched, page, PER_PAGE);
        page = current.index();

        rendered.clear();
        int slot = 0;
        for (AdminPlayerView view : current.items()) {
            boolean active = view.mitigation() != null;
            inventory.setItem(slot++, MenuItems.head(view.uuid(), view.name(),
                    (active ? MenuItems.BAD : MenuItems.LABEL) + view.name(),
                    List.of(MenuItems.line(AeroMessages.tr("gui.active"), active ? view.mitigation() : AeroMessages.tr("gui.none"),
                                    active ? MenuItems.BAD : MenuItems.MUTED),
                            MenuItems.line(AeroMessages.tr("gui.remaining"), active ? view.mitigationRemainingMs() + "ms" : "-"),
                            MenuItems.line(AeroMessages.tr("gui.applied_this_session"), String.valueOf(view.mitigationCount())),
                            MenuItems.line(AeroMessages.tr("gui.attacks_dropped"), String.valueOf(view.attacksSuppressed())),
                            MenuItems.line(AeroMessages.tr("gui.risk"), AdminStyle.number(view.risk(), 2)),
                            "",
                            MenuItems.note(AeroMessages.tr("gui.click_for_the_full_mitigation_history")))));
            rendered.add(view.uuid());
        }

        if (touched.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.LIME_STAINED_GLASS_PANE,
                    MenuItems.GOOD + AeroMessages.tr("gui.nothing_mitigated"),
                    MenuItems.note(AeroMessages.tr("gui.no_player_online_has_been_acted_on_this_session"))));
        }

        List<String> lore = new ArrayList<>();
        if (runtime == null || !runtime.config().mitigation().enabled()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.mitigation"), AeroMessages.tr("gui.disabled"), MenuItems.MUTED));
        } else {
            lore.add(MenuItems.line(AeroMessages.tr("gui.minimum_state"), AdminLabels.state(runtime.config().mitigation().minState())));
            lore.add(MenuItems.line(AeroMessages.tr("gui.cancels_attacks"),
                    runtime.config().mitigation().cancelAttacks() ? AeroMessages.tr("gui.yes") : AeroMessages.tr("gui.no")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.cap"), runtime.config().mitigation().maxPerHour() + AeroMessages.tr("gui.per_hour")));
        }
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.mitigation_is_applied_by_the_configured_rules_this_screen_re")
                        + AeroMessages.tr("gui.it_cannot_start_or_stop_one"), 44));
        inventory.setItem(49, MenuItems.item(Material.IRON_BARS, MenuItems.HEADER + AeroMessages.tr("gui.mitigation_rules"), lore));

        inventory.setItem(45, MenuItems.previousPage(current.hasPrevious()));
        inventory.setItem(53, MenuItems.nextPage(current.hasNext()));
        footer(inventory, this::back, AeroMessages.tr("gui.the_aero_menu"));
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
        if (!permitted(AdminPermissions.MITIGATION)) {
            deny(AdminPermissions.MITIGATION);
            return;
        }
        gui.show(new HistoryMenu(gui, viewer, rendered.get(slot), HistoryMenu.Kind.MITIGATION, false));
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
