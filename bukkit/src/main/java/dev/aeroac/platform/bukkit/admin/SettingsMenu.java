package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.admin.AdminConfig;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminViewMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Interface settings: the indicator mode, and a read-only view of what the configuration says.
 *
 * <p>Nothing on this screen changes detection. There is no threshold to drag, no head to switch off
 * and no mitigation to enable, because every one of those belongs in a file an operator edits
 * deliberately and reloads, not in a menu somebody can fat-finger during a fight.
 */
public final class SettingsMenu extends AeroMenu {
    private static final int SLOT_OFF = 10;
    private static final int SLOT_SUSPICIOUS = 11;
    private static final int SLOT_ALL = 12;
    private static final int SLOT_AUTO = 13;
    private static final int SLOT_CONFIG = 15;

    public SettingsMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.aero_settings"); }

    @Override public String permission() { return AdminPermissions.GUI; }

    @Override protected int rows() { return 3; }

    @Override protected void draw(Inventory inventory) {
        AdminViewMode active = service().viewMode(viewer.getUniqueId());
        AdminConfig settings = service().config();

        inventory.setItem(SLOT_OFF, mode(AdminViewMode.OFF, active, Material.GRAY_STAINED_GLASS_PANE,
                AeroMessages.tr("gui.settings.mode_off")));
        inventory.setItem(SLOT_SUSPICIOUS, mode(AdminViewMode.SUSPICIOUS, active, Material.ORANGE_STAINED_GLASS_PANE,
                AeroMessages.tr("gui.settings.mode_suspicious")));
        inventory.setItem(SLOT_ALL, mode(AdminViewMode.ALL, active, Material.LIME_STAINED_GLASS_PANE,
                AeroMessages.tr("gui.settings.mode_all")));
        inventory.setItem(SLOT_AUTO, mode(AdminViewMode.AUTO, active, Material.YELLOW_STAINED_GLASS_PANE,
                AeroMessages.tr("gui.settings.mode_auto")));

        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.screen_refresh"), settings.refreshMs() + "ms"));
        lore.add(MenuItems.line(AeroMessages.tr("gui.indicator_refresh"), settings.floating().refreshMs() + "ms"));
        lore.add(MenuItems.line(AeroMessages.tr("gui.indicators_enabled"), settings.floating().enabled() ? AeroMessages.tr("gui.yes") : AeroMessages.tr("gui.no")));
        lore.add(MenuItems.line(AeroMessages.tr("gui.default_mode"), AdminLabels.viewMode(settings.floating().defaultMode())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.recording_target"),
                settings.training().targetDurationSeconds() + AeroMessages.tr("gui.s")
                        + settings.training().targetAttackWindows() + AeroMessages.tr("gui.windows")));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.read_only_edit_neural_gui_in_the_configuration_and_run_grim")
                        + AeroMessages.tr("gui.open_screens_are_closed_so_none_of_them_outlives_the_setting"), 44));
        inventory.setItem(SLOT_CONFIG, MenuItems.item(Material.REPEATER,
                MenuItems.HEADER + AeroMessages.tr("gui.configuration"), lore));

        footer(inventory, this::back, AeroMessages.tr("gui.the_aero_menu"));
    }

    private org.bukkit.inventory.ItemStack mode(AdminViewMode mode, AdminViewMode active,
                                                Material material, String description) {
        boolean selected = mode == active;
        List<String> lore = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED, description, 40));
        lore.add("");
        lore.add(selected ? MenuItems.GOOD + AeroMessages.tr("gui.selected") : MenuItems.note(AeroMessages.tr("gui.click_to_select")));
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.only_you_can_see_these_indicators")));
        return MenuItems.item(material,
                (selected ? MenuItems.GOOD : MenuItems.LABEL) + AeroMessages.tr("gui.view") + AdminLabels.viewMode(mode), lore);
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
        AdminViewMode chosen = switch (slot) {
            case SLOT_OFF -> AdminViewMode.OFF;
            case SLOT_SUSPICIOUS -> AdminViewMode.SUSPICIOUS;
            case SLOT_ALL -> AdminViewMode.ALL;
            case SLOT_AUTO -> AdminViewMode.AUTO;
            default -> null;
        };
        if (chosen == null) return;
        if (!permitted(AdminPermissions.VIEW)) {
            deny(AdminPermissions.VIEW);
            return;
        }
        var self = gui.tracked(viewer.getUniqueId());
        if (self == null) {
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.your_connection_is_not_tracked_so_indicators_cannot_be_sent"));
            return;
        }
        service().viewMode(self, chosen);
        viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.indicator_mode") + AdminLabels.viewMode(chosen));
        redraw();
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
