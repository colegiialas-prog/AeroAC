package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * One screen, owned by the administrator looking at it.
 *
 * <p>The inventory is its own holder, which is how a click is routed: the listener asks the clicked
 * inventory who owns it and gets this object back, so no map of open screens has to be kept in sync
 * with a player's actual open window. A screen that is closed stops existing, and a screen for a
 * player who logged out is collected with them.
 *
 * <p>Redrawing replaces item stacks in place rather than reopening the window. Reopening steals the
 * cursor and closes anything the operator was mid-way through; replacing the contents of a live
 * inventory updates it under their hands, which is what makes a one-second refresh usable.
 */
public abstract class AeroMenu implements InventoryHolder {
    protected final BukkitAdminGui gui;
    protected final Player viewer;
    private Inventory inventory;

    protected AeroMenu(BukkitAdminGui gui, Player viewer) {
        this.gui = gui;
        this.viewer = viewer;
    }

    /** Legacy-coloured title. Changing it needs a reopen, so titles never carry live numbers. */
    protected abstract String title();

    protected abstract int rows();

    /** Fills the inventory. Called on open and on every refresh; must not block. */
    protected abstract void draw(Inventory inventory);

    /** Handles a click on a slot of this screen. The event is already cancelled. */
    public abstract void click(InventoryClickEvent event);

    /** Whether this screen should keep redrawing itself while it is open. */
    public boolean live() { return true; }

    public String permission() { return AdminPermissions.GUI; }

    public final boolean allowed() {
        return service().config().enabled() && permitted(permission());
    }

    @Override public @NotNull Inventory getInventory() {
        if (inventory == null) {
            String label = title();
            if (label.length() > 32) label = label.substring(0, 32);
            if (label.endsWith("\u00a7")) label = label.substring(0, label.length() - 1);
            inventory = Bukkit.createInventory(this, rows() * 9, label);
        }
        return inventory;
    }

    public final void open() {
        Inventory target = getInventory();
        redraw();
        viewer.openInventory(target);
    }

    public final void redraw() {
        Inventory target = getInventory();
        target.clear();
        draw(target);
        MenuItems.fill(target);
    }

    protected AdminService service() { return gui.service(); }

    protected boolean permitted(String permission) {
        return viewer.hasPermission(permission) || viewer.hasPermission(AdminPermissions.ADMIN);
    }

    protected void lockSlot(Inventory inventory, int slot, String permission) {
        if (!permitted(permission)) inventory.setItem(slot, MenuItems.item(org.bukkit.Material.BARRIER,
                MenuItems.MUTED + "LOCKED", MenuItems.note("Requires " + permission)));
    }

    /** Standard footer: back where it belongs, close on the right, page arrows around them. */
    protected void footer(Inventory inventory, Runnable back, String backTarget) {
        int base = (rows() - 1) * 9;
        if (back != null) inventory.setItem(base + 3, MenuItems.back(backTarget));
        inventory.setItem(base + 5, MenuItems.close());
    }

    protected static boolean isFooterBack(int slot, int rows) { return slot == (rows - 1) * 9 + 3; }

    protected static boolean isFooterClose(int slot, int rows) { return slot == (rows - 1) * 9 + 5; }

    /** Refuses the screen with one line rather than opening an empty window. */
    protected void deny(String permission) {
        viewer.sendMessage(MenuItems.BAD + "You do not have " + permission + ".");
    }

    /** The command sender behind this viewer, or null when the platform cannot produce one. */
    protected ac.grim.grimac.platform.api.sender.Sender senderOf() {
        return gui.sender(viewer.getUniqueId());
    }

    /**
     * Starts or stops streaming live telemetry for one player to this administrator.
     *
     * <p>Goes through the same monitor the {@code /neural monitor} command uses, on the target's
     * event loop, because that is where the watcher set is read from the packet path. The interface
     * adds a button; it does not add a second way to watch somebody.
     */
    protected void toggleWatch(java.util.UUID target) {
        if (!permitted(AdminPermissions.MONITOR)) {
            deny(AdminPermissions.MONITOR);
            return;
        }
        var player = gui.tracked(target);
        if (player == null) {
            viewer.sendMessage(MenuItems.MUTED + "That player is no longer online.");
            return;
        }
        var sender = senderOf();
        if (sender == null) {
            viewer.sendMessage(MenuItems.MUTED + "Your connection is not tracked; use /neural monitor instead.");
            return;
        }
        var runtime = gui.neural().runtime();
        boolean enable = runtime == null || !runtime.monitor().watching(target, viewer.getUniqueId());
        player.runSafely(() -> ac.grim.grimac.neural.NeuralMessages.send(sender,
                gui.neural().monitor(player, sender, enable)));
    }
}
