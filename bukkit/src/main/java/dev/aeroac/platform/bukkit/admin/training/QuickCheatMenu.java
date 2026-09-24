package dev.aeroac.platform.bukkit.admin.training;

import dev.aeroac.locale.AeroMessages;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.training.RecordingDraft;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.platform.bukkit.admin.AeroMenu;
import dev.aeroac.platform.bukkit.admin.BukkitAdminGui;
import dev.aeroac.platform.bukkit.admin.MenuItems;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one question a CHEAT recording cannot skip: which cheat.
 *
 * <p>Everything else about a cheat session may be left unset and later filled in, but the family
 * may not: a CHEAT session with no family cannot be grouped during evaluation, and the metadata
 * record refuses one. So the quick path for CHEAT is exactly one extra click, on this screen, and
 * then the recorder is running.
 *
 * <p>Assist strength is deliberately not asked here. Unset on a CHEAT session means UNKNOWN, which
 * is the honest record of "nobody wrote it down" — unlike NONE, which would claim the cheat was
 * giving no help at all and quietly poison every result grouped by strength.
 */
public final class QuickCheatMenu extends AeroMenu {
    private static final int FIRST = 9;
    /** Clear of the footer row, which owns 30 and 32. */
    private static final int SLOT_FULL_FORM = 22;

    private final UUID target;
    private final String targetName;
    private final List<String> rendered = new ArrayList<>();

    public QuickCheatMenu(BukkitAdminGui gui, Player viewer, UUID target, String targetName) {
        super(gui, viewer);
        this.target = target;
        this.targetName = targetName;
    }

    @Override protected String title() {
        return MenuItems.HEADER + AeroMessages.tr("gui.quick.cheat_title");
    }

    @Override public String permission() { return AdminPermissions.TRAINING_RECORD; }

    @Override protected int rows() { return 4; }

    @Override public boolean live() { return false; }

    @Override protected void draw(Inventory inventory) {
        inventory.setItem(4, MenuItems.item(Material.RED_CONCRETE,
                MenuItems.BAD + AeroMessages.tr("gui.quick.cheat_title"),
                MenuItems.line(AeroMessages.tr("gui.training.player"), targetName),
                "",
                MenuItems.note(AeroMessages.tr("gui.quick.cheat_pick")),
                MenuItems.note(AeroMessages.tr("gui.quick.cheat_assist_note"))));

        rendered.clear();
        int slot = FIRST;
        for (String family : service().config().training().cheatFamilies()) {
            // A long configured list must not land on the button that leads to the long form.
            if (slot == SLOT_FULL_FORM) slot++;
            if (slot >= 27) break;
            inventory.setItem(slot++, MenuItems.item(Material.PAPER, MenuItems.LABEL + family,
                    MenuItems.note(AeroMessages.tr("gui.quick.cheat_start_with") + family)));
            rendered.add(family);
        }

        inventory.setItem(SLOT_FULL_FORM, MenuItems.item(Material.WRITABLE_BOOK,
                MenuItems.HEADER + AeroMessages.tr("gui.quick.full_wizard"),
                MenuItems.note(AeroMessages.tr("gui.quick.full_wizard_note"))));
        footer(inventory, this::back, AeroMessages.tr("gui.quick.title"));
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
        if (slot == SLOT_FULL_FORM) {
            RecordingDraft draft = service().draft(viewer.getUniqueId());
            draft.target(target, targetName).label(DatasetMetadata.Label.CHEAT);
            gui.show(new WizardMenu(gui, viewer, WizardMenu.Step.CHEAT_FAMILY));
            return;
        }
        int index = slot - FIRST;
        if (index < 0 || index >= rendered.size()) return;
        RecordingDraft draft = new RecordingDraft()
                .target(target, targetName)
                .label(DatasetMetadata.Label.CHEAT)
                .cheatFamily(rendered.get(index));
        if (startRecording(draft)) gui.show(new RecordingsMenu(gui, viewer));
    }

    private void back() {
        gui.show(new QuickRecordMenu(gui, viewer));
    }
}
