package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminService;
import dev.aeroac.platform.bukkit.AeroACBukkitLoaderPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
                MenuItems.MUTED + AeroMessages.tr("gui.locked"), MenuItems.note(AeroMessages.tr("gui.requires") + permission)));
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
        viewer.sendMessage(MenuItems.BAD + AeroMessages.tr("gui.you_do_not_have") + permission + ".");
    }

    /**
     * Hands work back to the server's main thread.
     *
     * <p>Anything a screen asks of a backend — the training service, the runtime — answers on its own
     * thread. A reply consumer that touched an inventory or sent a message from there would be
     * mutating Bukkit state off the thread that owns it, so every reply this package hands out hops
     * back through here first. It is the only place in the admin screens that schedules.
     */
    protected static void onMain(Runnable action) {
        Bukkit.getScheduler().runTask(AeroACBukkitLoaderPlugin.LOADER, action);
    }

    /**
     * Asks one question on its own screen before anything outside the server is touched.
     *
     * @param titleKey    catalog key for the screen title
     * @param questionKey catalog key for the sentence the operator reads before answering
     * @param confirmKey  catalog key labelling the confirming button
     * @param action      what to run when confirmed; must not block, it runs inside the click
     * @param back        builds the screen to return to, freshly, so it never shows stale numbers
     */
    protected void confirm(String titleKey, String questionKey, Object[] arguments, String confirmKey,
                           List<String> detail, Runnable action, java.util.function.Supplier<AeroMenu> back) {
        gui.show(new ConfirmMenu(gui, viewer, titleKey, questionKey, arguments, detail, confirmKey,
                action, back));
    }

    /** The command sender behind this viewer, or null when the platform cannot produce one. */
    protected dev.aeroac.platform.api.sender.Sender senderOf() {
        return gui.sender(viewer.getUniqueId());
    }

    /**
     * Opens a dataset session from a finished draft, wherever the operator filled it in.
     *
     * <p>One path for both routes into recording. The wizard collects every field and the quick
     * start collects two; what they hand over is the same draft object, validated by the same rules
     * and opened through the same recorder entry point, so the file on disk cannot depend on which
     * screen an operator preferred.
     *
     * @return false when the session was refused, with the operator already told why
     */
    protected boolean startRecording(dev.aeroac.neural.admin.training.RecordingDraft draft) {
        if (!permitted(AdminPermissions.TRAINING_RECORD)) {
            deny(AdminPermissions.TRAINING_RECORD);
            return false;
        }
        String problem = draft.problemMessage();
        if (problem != null) {
            viewer.sendMessage(MenuItems.BAD + problem);
            return false;
        }
        var player = gui.tracked(draft.targetUuid());
        if (player == null) {
            viewer.sendMessage(MenuItems.BAD + AeroMessages.tr("gui.training.that_player_is_no_longer_online"));
            return false;
        }
        var recipient = senderOf();
        long epoch = service().generation();
        player.runSafely(() -> {
            // A reload between the click and the event loop replaces the runtime; the session that
            // would open then belongs to a configuration nobody asked for.
            if (service().generation() != epoch || player.getNeuralState().disconnected) return;
            gui.neural().startSession(player, draft.label(), draft.cheatFamily(), draft.clientFamily(),
                    draft.configuration(), draft.scenario(), draft.assistArgument(), draft.notes(),
                    message -> {
                        dev.aeroac.neural.NeuralMessages.send(recipient, message);
                        service().invalidateDatasetSummary();
                    });
        });
        service().clearDraft(viewer.getUniqueId());
        service().invalidateDatasetSummary();
        return true;
    }

    /**
     * Starts or stops streaming live telemetry for one player to this administrator.
     *
     * <p>Goes through the same monitor the {@code /aero neural monitor} command uses, on the target's
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
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.that_player_is_no_longer_online"));
            return;
        }
        var sender = senderOf();
        if (sender == null) {
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.your_connection_is_not_tracked_use_neural_monitor_instead"));
            return;
        }
        var runtime = gui.neural().runtime();
        boolean enable = runtime == null || !runtime.monitor().watching(target, viewer.getUniqueId());
        player.runSafely(() -> dev.aeroac.neural.NeuralMessages.send(sender,
                gui.neural().monitor(player, sender, enable)));
    }
}
