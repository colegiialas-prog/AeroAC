package dev.aeroac.platform.bukkit.admin.training;

import dev.aeroac.locale.AeroMessages;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminSnapshot;
import dev.aeroac.neural.admin.AdminStyle;
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
 * Start recording in one click: pick a player, the session opens.
 *
 * <p>The wizard asks eight questions because the metadata record has eight fields. Seven of them
 * are optional, and an operator standing next to a test subject wants the recorder running now, not
 * after a form. So this screen collects the two things that are actually required — who, and which
 * label — and leaves the rest unset, which is exactly what "unset" is for. A session recorded this
 * way is a complete, valid session; it simply says less about how it was produced.
 *
 * <p>The full wizard stays one shift-click away for a recording that is worth describing, and the
 * fields can be filled in from the command surface afterwards.
 */
public final class QuickRecordMenu extends AeroMenu {
    private static final int PER_PAGE = 36;
    private static final int SLOT_LEGEND = 4;
    private static final int FIRST = 9;

    private int page;
    private final List<UUID> rendered = new ArrayList<>();

    public QuickRecordMenu(BukkitAdminGui gui, Player viewer) {
        this(gui, viewer, 0);
    }

    public QuickRecordMenu(BukkitAdminGui gui, Player viewer, int page) {
        super(gui, viewer);
        this.page = page;
    }

    @Override protected String title() {
        return MenuItems.HEADER + AeroMessages.tr("gui.quick.title");
    }

    @Override public String permission() { return AdminPermissions.TRAINING_RECORD; }

    @Override protected int rows() { return 6; }

    /** An operator is aiming at a head here; a redraw under the cursor moves the target. */
    @Override public boolean live() { return false; }

    @Override protected void draw(Inventory inventory) {
        inventory.setItem(SLOT_LEGEND, legend());
        recordingWarning(inventory, 8);

        AdminSnapshot.Page<AdminPlayerView> current =
                AdminSnapshot.page(service().snapshot().players(), page, PER_PAGE);
        page = current.index();
        rendered.clear();

        int slot = FIRST;
        for (AdminPlayerView view : current.items()) {
            boolean recording = view.isRecording();
            List<String> lore = new ArrayList<>();
            if (recording) {
                lore.add(MenuItems.line(AeroMessages.tr("gui.training.already_recording"),
                        view.recording().shortLabel(), MenuItems.WARN));
                lore.add(MenuItems.note(AeroMessages.tr("gui.quick.stop_first")));
            } else {
                lore.add(MenuItems.line(AeroMessages.tr("gui.training.ping"),
                        view.ping() < 0 ? AdminStyle.NO_DATA : view.ping() + "ms"));
                lore.add("");
                lore.add(MenuItems.GOOD + AeroMessages.tr("gui.quick.left_click"));
                lore.add(MenuItems.BAD + AeroMessages.tr("gui.quick.right_click"));
                lore.add(MenuItems.note(AeroMessages.tr("gui.quick.shift_click")));
            }
            inventory.setItem(slot++, MenuItems.head(view.uuid(), view.name(),
                    (recording ? MenuItems.MUTED : MenuItems.LABEL) + view.name(), lore));
            rendered.add(recording ? null : view.uuid());
        }
        if (current.total() == 0) {
            inventory.setItem(22, MenuItems.item(Material.BARRIER,
                    MenuItems.MUTED + AeroMessages.tr("gui.training.nobody_online")));
        }

        if (current.hasPrevious()) {
            inventory.setItem(45, MenuItems.item(Material.ARROW,
                    MenuItems.HEADER + AeroMessages.tr("gui.training.previous")));
        }
        if (current.hasNext()) {
            inventory.setItem(53, MenuItems.item(Material.ARROW,
                    MenuItems.HEADER + AeroMessages.tr("gui.training.next")));
        }
        footer(inventory, this::back, AeroMessages.tr("gui.training.the_aero_menu"));
    }

    private org.bukkit.inventory.ItemStack legend() {
        List<String> lore = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.quick.intro"), 44));
        lore.add("");
        lore.add(MenuItems.GOOD + AeroMessages.tr("gui.quick.left_click"));
        lore.add(MenuItems.BAD + AeroMessages.tr("gui.quick.right_click"));
        lore.add(MenuItems.note(AeroMessages.tr("gui.quick.shift_click")));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED, AeroMessages.tr("gui.quick.unset_note"), 44));
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.quick.command_hint")));
        return MenuItems.item(Material.LIME_CONCRETE, MenuItems.HEADER + AeroMessages.tr("gui.quick.title"), lore);
    }

    /** Recording off is the one thing that makes every click on this screen fail silently later. */
    private void recordingWarning(Inventory inventory, int slot) {
        var config = gui.neural().config();
        if (config != null && config.recordingEnabled()) return;
        List<String> lore = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.wizard.recording_off_detail"), 44));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED, AeroMessages.tr("gui.wizard.recording_off_where"), 44));
        inventory.setItem(slot, MenuItems.item(Material.BARRIER,
                MenuItems.BAD + AeroMessages.tr("gui.wizard.recording_off"), lore));
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
        int index = slot - FIRST;
        if (index < 0 || index >= rendered.size()) return;
        UUID target = rendered.get(index);
        if (target == null) return;   // already recording; the head says so

        AdminPlayerView view = service().snapshot().find(target);
        String name = view == null ? "?" : view.name();

        if (event.isShiftClick()) {
            // The long way, for a session somebody will want to describe later.
            RecordingDraft draft = service().draft(viewer.getUniqueId());
            draft.target(target, name);
            gui.show(new WizardMenu(gui, viewer, WizardMenu.Step.LABEL));
            return;
        }
        if (event.isRightClick()) {
            gui.show(new QuickCheatMenu(gui, viewer, target, name));
            return;
        }
        if (startRecording(new RecordingDraft().target(target, name).label(DatasetMetadata.Label.LEGIT))) {
            gui.show(new RecordingsMenu(gui, viewer));
        }
    }

    private void back() {
        gui.show(new TrainingMenu(gui, viewer));
    }
}
