package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.locale.AeroMessages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.function.Supplier;

/**
 * One question, two answers.
 *
 * <p>Anything that reaches outside the server — starting a training run, cancelling one, bringing the
 * runtime up — is asked here first, on its own screen, with the answer stated plainly. A single
 * click that opens a socket to a training service is a click an operator will eventually make by
 * accident while a fight is happening, and the cost of that accident is a job they did not want.
 *
 * <p>The confirmation itself is not the safety: the action handed in is already asynchronous, and
 * this screen never waits for it. What the screen adds is that the operator has read one sentence
 * about what is about to happen before anything is scheduled.
 *
 * <p>The back arrow returns to a freshly built parent, so a screen that has been sitting open does
 * not reappear with stale numbers.
 */
public final class ConfirmMenu extends AeroMenu {
    private static final int SLOT_CONFIRM = 11;
    private static final int SLOT_CANCEL = 15;

    private final String titleKey;
    private final String questionKey;
    private final Object[] questionArguments;
    private final List<String> detail;
    private final String confirmKey;
    private final Runnable action;
    private final Supplier<AeroMenu> back;

    public ConfirmMenu(BukkitAdminGui gui, Player viewer, String titleKey, String questionKey,
                       Object[] questionArguments, List<String> detail, String confirmKey,
                       Runnable action, Supplier<AeroMenu> back) {
        super(gui, viewer);
        this.titleKey = titleKey;
        this.questionKey = questionKey;
        this.questionArguments = questionArguments == null ? new Object[0] : questionArguments;
        this.detail = detail == null ? List.of() : detail;
        this.confirmKey = confirmKey;
        this.action = action;
        this.back = back;
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr(titleKey); }

    @Override protected int rows() { return 3; }

    /** A question is answered once, not watched; nothing here redraws on a timer. */
    @Override public boolean live() { return false; }

    @Override protected void draw(Inventory inventory) {
        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add(MenuItems.VALUE + question());
        lore.add("");
        lore.addAll(detail);
        inventory.setItem(SLOT_CONFIRM, MenuItems.item(Material.LIME_STAINED_GLASS_PANE,
                MenuItems.GOOD + "§l" + AeroMessages.tr(confirmKey), lore));
        inventory.setItem(SLOT_CANCEL, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.MUTED + "§l" + AeroMessages.tr("gui.confirm.cancel"),
                MenuItems.note(AeroMessages.tr("gui.confirm.cancel_note"))));
        footer(inventory, () -> gui.show(back.get()), AeroMessages.tr("gui.confirm.back_where"));
    }

    @Override public void click(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (isFooterClose(slot, rows())) {
            viewer.closeInventory();
            return;
        }
        if (isFooterBack(slot, rows())) {
            gui.show(back.get());
            return;
        }
        if (slot == SLOT_CANCEL) {
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.confirm.cancelled_nothing_changed"));
            gui.show(back.get());
            return;
        }
        if (slot == SLOT_CONFIRM) {
            // The action schedules its own work; this must stay a click, not a wait.
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.confirm.sent"));
            gui.show(back.get());
            action.run();
        }
    }

    /** The question line for this screen, for tests and for the action's own reply. */
    public String question() {
        return AeroMessages.tr(questionKey, questionArguments);
    }
}
