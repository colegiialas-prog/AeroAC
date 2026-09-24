package ac.grim.grimac.platform.bukkit.admin.training;

import ac.grim.grimac.neural.admin.AdminPermissions;

import ac.grim.grimac.neural.admin.AdminConfig;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.admin.RecordingView;
import ac.grim.grimac.platform.bukkit.admin.AeroMenu;
import ac.grim.grimac.platform.bukkit.admin.BukkitAdminGui;
import ac.grim.grimac.platform.bukkit.admin.MenuItems;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every dataset session running right now, with its progress and its live quality counters.
 *
 * <p>The progress bars are measured against goals an operator configured for themselves. They say
 * how far a recording has got, not whether it is any good: a session that hits both targets has not
 * been audited, is not reviewed, and is not known to be usable. The word for that arrives later,
 * from the offline tooling, and this screen is careful never to borrow it.
 */
public final class RecordingsMenu extends AeroMenu {
    private int page;
    private final List<UUID> rendered = new ArrayList<>();

    public RecordingsMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + "AERO › ACTIVE RECORDINGS"; }

    @Override public String permission() { return AdminPermissions.TRAINING; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        AdminConfig.Training goals = service().config().training();
        List<RecordingView> recordings = service().snapshot().recordings();

        rendered.clear();
        int slot = 0;
        var selected = ac.grim.grimac.neural.admin.AdminSnapshot.page(recordings, page, 45);
        page = selected.index();
        inventory.setItem(45, MenuItems.previousPage(selected.hasPrevious()));
        inventory.setItem(53, MenuItems.nextPage(selected.hasNext()));
        for (RecordingView recording : selected.items()) {
            inventory.setItem(slot++, tile(recording, goals));
            rendered.add(recording.playerUuid());
        }
        if (recordings.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    MenuItems.MUTED + "NOTHING RECORDING",
                    MenuItems.note("Start one from the training centre, or with"),
                    MenuItems.note("/neural dataset start <player> ...")));
        }

        inventory.setItem(49, MenuItems.item(Material.PAPER, MenuItems.HEADER + "TARGETS",
                MenuItems.line("Duration", goals.targetDurationSeconds() + "s"),
                MenuItems.line("Attack windows", String.valueOf(goals.targetAttackWindows())),
                "",
                MenuItems.note("Recommendations for the operator. They are not"),
                MenuItems.note("part of the label and not a quality measure.")));
        footer(inventory, this::back, "the training centre");
    }

    private org.bukkit.inventory.ItemStack tile(RecordingView recording, AdminConfig.Training goals) {
        List<String> lore = new ArrayList<>();
        String stateColour = switch (recording.state()) {
            case RECORDING -> MenuItems.GOOD;
            case CLOSING -> MenuItems.WARN;
            case FAILED -> MenuItems.BAD;
        };
        lore.add(MenuItems.line("State", recording.state().name(), stateColour));
        lore.add(MenuItems.line("Label", recording.label().name(), MenuItems.colourOf(recording.label())));
        if (recording.cheatFamily() != null && !recording.cheatFamily().isBlank()) {
            lore.add(MenuItems.line("Cheat family", recording.cheatFamily()));
        }
        lore.add(MenuItems.line("Client", AdminStyle.blankIfEmpty(recording.clientFamily(), "(unset)")));
        lore.add(MenuItems.line("Configuration",
                AdminStyle.blankIfEmpty(recording.configuration(), "(unset)")));
        lore.add(MenuItems.line("Assist", recording.assistStrength() == null ? "(unset)"
                : recording.assistStrength().name()));
        lore.add(MenuItems.line("Scenario", AdminStyle.blankIfEmpty(recording.scenario(), "(unset)")));
        lore.add("");
        lore.add(MenuItems.LABEL + "Duration: " + MenuItems.VALUE
                + AdminStyle.clock(recording.durationSeconds()) + MenuItems.MUTED + " / "
                + AdminStyle.clock(goals.targetDurationSeconds()) + "  " + MenuItems.GOOD
                + AdminStyle.bar(recording.durationProgress(goals.targetDurationSeconds()), 10));
        lore.add(MenuItems.progress("Attack windows", recording.attackWindows(),
                goals.targetAttackWindows(), MenuItems.GOOD));
        lore.add("");
        lore.add(MenuItems.line("Frames", String.valueOf(recording.frames())));
        lore.add(MenuItems.line("Attacks", String.valueOf(recording.attacks())));
        lore.add(MenuItems.line("Swings", String.valueOf(recording.swings())));
        lore.add(MenuItems.line("Queued", String.valueOf(recording.queued())));
        lore.add(MenuItems.line("Dropped records", String.valueOf(recording.dropped()),
                recording.dropped() > 0 ? MenuItems.BAD : MenuItems.VALUE));
        lore.add("");
        lore.add(MenuItems.line("Target known", AdminStyle.percent(recording.targetKnown())));
        lore.add(MenuItems.line("Aim error known", AdminStyle.percent(recording.aimErrorKnown())));
        lore.add(MenuItems.line("Geometry known", AdminStyle.percent(recording.geometryKnown())));
        if (recording.qualityWarning()) {
            lore.add("");
            lore.add(MenuItems.WARN + "QUALITY WARNING");
            lore.addAll(MenuItems.wrap(MenuItems.MUTED, warning(recording), 44));
        }
        if (recording.failure() != null) {
            lore.add("");
            lore.add(MenuItems.line("Failure", recording.failure(), MenuItems.BAD));
        }
        lore.add("");
        lore.add(MenuItems.note("Click for details and stop."));

        return MenuItems.head(recording.playerUuid(), recording.playerName(),
                MenuItems.colourOf(recording.label()) + "● " + recording.playerName()
                        + MenuItems.MUTED + " " + recording.shortLabel(), lore);
    }

    private static String warning(RecordingView recording) {
        if (recording.failure() != null) return "The recorder failed; the session is closing.";
        if (recording.dropped() > 0) {
            return "Records were dropped because the write queue filled up. The recording is "
                    + "incomplete and the audit will see that.";
        }
        if (recording.movementGaps() > 0) return "Movement gaps interrupted the recorded stream.";
        if (recording.attackWindows() == 0) {
            return "No attack window has been buildable yet. Check that the fight is producing "
                    + "attacks and that the configured window fits them.";
        }
        return "Most frames carry no target. A model trained on this will be learning from "
                + "samples with no combat context in them.";
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
        if (slot == 45 && page > 0) { page--; redraw(); return; }
        if (slot == 53) { page++; redraw(); return; }
        if (slot >= rendered.size() || slot >= 45) return;
        gui.show(new RecordingDetailMenu(gui, viewer, rendered.get(slot)));
    }

    private void back() {
        gui.show(new TrainingMenu(gui, viewer));
    }
}
