package dev.aeroac.platform.bukkit.admin.training;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminPermissions;

import dev.aeroac.neural.admin.AdminConfig;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.admin.RecordingView;
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

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.training.aero_active_recordings"); }

    @Override public String permission() { return AdminPermissions.TRAINING; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        AdminConfig.Training goals = service().config().training();
        List<RecordingView> recordings = service().snapshot().recordings();

        rendered.clear();
        int slot = 0;
        var selected = dev.aeroac.neural.admin.AdminSnapshot.page(recordings, page, 45);
        page = selected.index();
        inventory.setItem(45, MenuItems.previousPage(selected.hasPrevious()));
        inventory.setItem(53, MenuItems.nextPage(selected.hasNext()));
        for (RecordingView recording : selected.items()) {
            inventory.setItem(slot++, tile(recording, goals));
            rendered.add(recording.playerUuid());
        }
        if (recordings.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    MenuItems.MUTED + AeroMessages.tr("gui.training.nothing_recording"),
                    MenuItems.note(AeroMessages.tr("gui.training.start_one_from_the_training_centre_or_with")),
                    MenuItems.note(AeroMessages.tr("gui.training.neural_dataset_start_player"))));
        }

        inventory.setItem(49, MenuItems.item(Material.PAPER, MenuItems.HEADER + AeroMessages.tr("gui.training.targets"),
                MenuItems.line(AeroMessages.tr("gui.training.duration"), goals.targetDurationSeconds() + "s"),
                MenuItems.line(AeroMessages.tr("gui.training.attack_windows"), String.valueOf(goals.targetAttackWindows())),
                "",
                MenuItems.note(AeroMessages.tr("gui.training.recommendations_for_the_operator_they_are_not")),
                MenuItems.note(AeroMessages.tr("gui.training.part_of_the_label_and_not_a_quality_measure"))));
        footer(inventory, this::back, AeroMessages.tr("gui.training.the_training_centre"));
    }

    private org.bukkit.inventory.ItemStack tile(RecordingView recording, AdminConfig.Training goals) {
        List<String> lore = new ArrayList<>();
        String stateColour = switch (recording.state()) {
            case RECORDING -> MenuItems.GOOD;
            case CLOSING -> MenuItems.WARN;
            case FAILED -> MenuItems.BAD;
        };
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.state"), AdminLabels.recording(recording.state()), stateColour));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.label"), AdminLabels.datasetLabel(recording.label()), MenuItems.colourOf(recording.label())));
        if (recording.cheatFamily() != null && !recording.cheatFamily().isBlank()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.cheat_family"), recording.cheatFamily()));
        }
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.client"), AdminStyle.blankIfEmpty(recording.clientFamily(), AeroMessages.tr("gui.training.unset"))));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.configuration"),
                AdminStyle.blankIfEmpty(recording.configuration(), AeroMessages.tr("gui.training.unset"))));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.assist"), recording.assistStrength() == null ? AeroMessages.tr("gui.training.unset")
                : AdminLabels.assistStrength(recording.assistStrength())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.scenario"), AdminStyle.blankIfEmpty(recording.scenario(), AeroMessages.tr("gui.training.unset"))));
        lore.add("");
        lore.add(MenuItems.LABEL + AeroMessages.tr("gui.training.duration_2") + MenuItems.VALUE
                + AdminStyle.clock(recording.durationSeconds()) + MenuItems.MUTED + " / "
                + AdminStyle.clock(goals.targetDurationSeconds()) + "  " + MenuItems.GOOD
                + AdminStyle.bar(recording.durationProgress(goals.targetDurationSeconds()), 10));
        lore.add(MenuItems.progress(AeroMessages.tr("gui.training.attack_windows"), recording.attackWindows(),
                goals.targetAttackWindows(), MenuItems.GOOD));
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.frames"), String.valueOf(recording.frames())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.attacks"), String.valueOf(recording.attacks())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.swings"), String.valueOf(recording.swings())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.queued"), String.valueOf(recording.queued())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.dropped_records"), String.valueOf(recording.dropped()),
                recording.dropped() > 0 ? MenuItems.BAD : MenuItems.VALUE));
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.target_known"), AdminStyle.percent(recording.targetKnown())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.aim_error_known"), AdminStyle.percent(recording.aimErrorKnown())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.geometry_known"), AdminStyle.percent(recording.geometryKnown())));
        if (recording.qualityWarning()) {
            lore.add("");
            lore.add(MenuItems.WARN + AeroMessages.tr("gui.training.quality_warning"));
            lore.addAll(MenuItems.wrap(MenuItems.MUTED, warning(recording), 44));
        }
        if (recording.failure() != null) {
            lore.add("");
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.failure"), recording.failure(), MenuItems.BAD));
        }
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.training.click_for_details_and_stop")));

        return MenuItems.head(recording.playerUuid(), recording.playerName(),
                MenuItems.colourOf(recording.label()) + "● " + recording.playerName()
                        + MenuItems.MUTED + " " + recording.shortLabel(), lore);
    }

    private static String warning(RecordingView recording) {
        if (recording.failure() != null) return "Ошибка записи; сессия закрывается.";
        if (recording.dropped() > 0) return "Очередь записи переполнена. Часть данных потеряна; аудит отметит неполную запись.";
        if (recording.movementGaps() > 0) return "Поток записи прерывался из-за пропусков движения.";
        if (recording.attackWindows() == 0) return "Окна атак ещё не сформированы. Проверьте наличие атак и размеры окна.";
        return "В большинстве кадров нет цели. Для обучения недостаточно боевого контекста.";
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
