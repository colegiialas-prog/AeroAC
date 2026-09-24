package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.admin.AdminConfig;
import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminViewMode;
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

    @Override protected String title() { return MenuItems.HEADER + "AERO › SETTINGS"; }

    @Override public String permission() { return AdminPermissions.GUI; }

    @Override protected int rows() { return 3; }

    @Override protected void draw(Inventory inventory) {
        AdminViewMode active = service().viewMode(viewer.getUniqueId());
        AdminConfig settings = service().config();

        inventory.setItem(SLOT_OFF, mode(AdminViewMode.OFF, active, Material.GRAY_DYE,
                "No indicator above anyone."));
        inventory.setItem(SLOT_SUSPICIOUS, mode(AdminViewMode.SUSPICIOUS, active, Material.ORANGE_DYE,
                "Only players at WATCH or above."));
        inventory.setItem(SLOT_ALL, mode(AdminViewMode.ALL, active, Material.LIME_DYE,
                "Everyone, including players the model scores as clean."));
        inventory.setItem(SLOT_AUTO, mode(AdminViewMode.AUTO, active, Material.YELLOW_DYE,
                "Like suspicious, and the tag disappears the moment they return to CLEAN."));

        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line("Screen refresh", settings.refreshMs() + "ms"));
        lore.add(MenuItems.line("Indicator refresh", settings.floating().refreshMs() + "ms"));
        lore.add(MenuItems.line("Indicators enabled", settings.floating().enabled() ? "yes" : "no"));
        lore.add(MenuItems.line("Default mode", settings.floating().defaultMode().name()));
        lore.add(MenuItems.line("Recording target",
                settings.training().targetDurationSeconds() + "s / "
                        + settings.training().targetAttackWindows() + " windows"));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                "Read-only. Edit neural.gui.* in the configuration and run /grim reload; "
                        + "open screens are closed so none of them outlives the settings they were built from.", 44));
        inventory.setItem(SLOT_CONFIG, MenuItems.item(Material.REPEATER,
                MenuItems.HEADER + "CONFIGURATION", lore));

        footer(inventory, this::back, "the Aero menu");
    }

    private org.bukkit.inventory.ItemStack mode(AdminViewMode mode, AdminViewMode active,
                                                Material material, String description) {
        boolean selected = mode == active;
        List<String> lore = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED, description, 40));
        lore.add("");
        lore.add(selected ? MenuItems.GOOD + "Selected" : MenuItems.note("Click to select"));
        lore.add("");
        lore.add(MenuItems.note("Only you can see these indicators."));
        return MenuItems.item(material,
                (selected ? MenuItems.GOOD : MenuItems.LABEL) + "VIEW " + mode.name(), lore);
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
            viewer.sendMessage(MenuItems.MUTED + "Your connection is not tracked, so indicators cannot be sent.");
            return;
        }
        service().viewMode(self, chosen);
        viewer.sendMessage(MenuItems.MUTED + "Indicator mode: " + chosen.name());
        redraw();
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
