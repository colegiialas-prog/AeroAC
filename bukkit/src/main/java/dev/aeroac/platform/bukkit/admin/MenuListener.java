package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.locale.AeroMessages;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Routes inventory events to the screen that owns them.
 *
 * <p>Every interaction with an Aero window is cancelled before anything else happens, including
 * clicks in the player's own inventory while an Aero window is open and every form of drag. An
 * administrator interface that can be duped into giving out items is worse than no interface, and
 * the safe default is to treat the whole window as read-only and let the screen opt in to a click.
 */
public final class MenuListener implements Listener {
    private final BukkitAdminGui gui;

    public MenuListener(BukkitAdminGui gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(holderOf(top) instanceof AeroMenu menu)) return;
        // Cancel first: an exception inside a screen must not leave the click live.
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (alreadyCancelled || event.getClickedInventory() != top) return;
        if (!player.getUniqueId().equals(menu.viewer.getUniqueId())
                || gui.current(player.getUniqueId()) != menu || !menu.allowed()) return;
        if (event.getClick() != org.bukkit.event.inventory.ClickType.LEFT
                && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
        try {
            menu.click(event);
        } catch (RuntimeException error) {
            player.sendMessage(MenuItems.BAD + AeroMessages.tr("gui.aero_could_not_handle_that_click") + error.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (holderOf(event.getView().getTopInventory()) instanceof AeroMenu) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (holderOf(event.getView().getTopInventory()) instanceof AeroMenu menu) {
            gui.forget(event.getPlayer().getUniqueId(), menu);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gui.forget(event.getPlayer().getUniqueId());
        gui.service().forgetViewer(event.getPlayer().getUniqueId());
    }

    private static InventoryHolder holderOf(Inventory inventory) {
        try {
            return inventory == null ? null : inventory.getHolder();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
