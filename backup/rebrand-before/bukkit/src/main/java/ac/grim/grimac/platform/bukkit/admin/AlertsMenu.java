package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.neural.admin.AdminPermissions;

import ac.grim.grimac.neural.admin.AdminConfig;
import ac.grim.grimac.neural.admin.AdminStyle;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Your own alert switch, and an explanation of when an alert can fire at all.
 *
 * <p>The toggle is per administrator and lasts for the session. The thresholds beside it are
 * configuration and are shown read-only: an operator who could raise the alert floor from a menu
 * would be changing what every other operator sees, from a screen that looks personal.
 */
public final class AlertsMenu extends AeroMenu {
    private static final int SLOT_TOGGLE = 13;

    public AlertsMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + "AERO › ALERTS"; }

    @Override public String permission() { return AdminPermissions.ALERTS; }

    @Override protected int rows() { return 3; }

    @Override protected void draw(Inventory inventory) {
        boolean muted = service().alerts().muted(viewer.getUniqueId());
        AdminConfig.Alerts settings = service().config().alerts();

        inventory.setItem(SLOT_TOGGLE, MenuItems.item(muted ? Material.GRAY_DYE : Material.LIME_DYE,
                muted ? MenuItems.MUTED + "ALERTS OFF" : MenuItems.GOOD + "ALERTS ON",
                MenuItems.note("Click to turn them " + (muted ? "on" : "off") + " for yourself.")));

        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line("Enabled on this server", settings.enabled() ? "yes" : "no",
                settings.enabled() ? MenuItems.GOOD : MenuItems.MUTED));
        lore.add(MenuItems.line("Minimum state", settings.minState().name(),
                MenuItems.colourOf(settings.minState())));
        lore.add(MenuItems.line("Throttle", settings.throttleSeconds() + "s"));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                "An alert fires when a player's state climbs to at least the minimum, and at most "
                        + "once per throttle for that player. A state that falls is recorded quietly, so "
                        + "somebody hovering on the boundary does not alert on every crossing.", 44));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                "Predictions never alert. The model produces an output every window; alerting on "
                        + "that would be a stream nobody reads.", 44));
        inventory.setItem(11, MenuItems.item(Material.BELL, MenuItems.HEADER + "WHEN ALERTS FIRE", lore));

        inventory.setItem(15, MenuItems.item(Material.PAPER, MenuItems.HEADER + "WHAT AN ALERT SAYS",
                MenuItems.note("[Aero] Player → SUSPICIOUS"),
                MenuItems.note("Risk 7.82 | " + AdminStyle.RISK_LABEL + " 91% | AIM"),
                "",
                MenuItems.note("[PROFILE] and [WATCH] in the message are"),
                MenuItems.note("clickable and run the matching command."),
                "",
                MenuItems.note("The percentage is a model output. It is not"),
                MenuItems.note("a measured probability that someone cheats.")));

        footer(inventory, this::back, "the Aero menu");
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
        if (slot == SLOT_TOGGLE) {
            boolean nowEnabled = service().alerts().muted(viewer.getUniqueId());
            service().alerts().toggle(viewer.getUniqueId(), nowEnabled);
            viewer.sendMessage(MenuItems.MUTED + "Aero alerts " + (nowEnabled ? "enabled" : "disabled")
                    + " for you.");
            redraw();
        }
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
