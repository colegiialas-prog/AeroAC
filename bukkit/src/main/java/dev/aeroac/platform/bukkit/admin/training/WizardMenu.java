package dev.aeroac.platform.bukkit.admin.training;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.admin.training.RecordingDraft;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.platform.bukkit.admin.AeroMenu;
import dev.aeroac.platform.bukkit.admin.BukkitAdminGui;
import dev.aeroac.platform.bukkit.admin.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The start-recording wizard: one decision per screen, in the order the metadata record needs them.
 *
 * <p>The wizard's job is to make an invalid session impossible to start. LEGIT is honest play and
 * its assist strength is always NONE; CHEAT always carries a family and never NONE, because a CHEAT
 * session claiming no assistance would poison every evaluation grouped by strength. Those rules
 * live in the metadata record and are enforced again here, before anything is opened, so an
 * operator finds out at the confirm screen rather than from an exception mid-session.
 *
 * <p>Free-text fields are filled with a command rather than a chat prompt: a suggested command
 * works identically on every server version and needs no chat listener that could swallow somebody's
 * message. Clicking the field hands the operator a ready-made command to complete.
 */
public final class WizardMenu extends AeroMenu {
    public enum Step {
        PLAYER("SELECT PLAYER"),
        LABEL("SELECT LABEL"),
        CHEAT_FAMILY("CHEAT FAMILY"),
        CLIENT("CLIENT FAMILY"),
        CONFIGURATION("CONFIGURATION"),
        ASSIST("ASSIST STRENGTH"),
        SCENARIO("SCENARIO"),
        CONFIRM("CONFIRM");

        private final String title;

        Step(String title) { this.title = title; }

        public String title() { return title; }
    }

    private static final int SLOT_CONFIRM = 49;
    private final Step step;
    private int page;
    private RecordingDraft confirmed;
    private boolean submitted;
    private final List<Runnable> actions = new ArrayList<>();

    public WizardMenu(BukkitAdminGui gui, Player viewer, Step step) {
        super(gui, viewer);
        this.step = step;
    }

    @Override protected String title() {
        return MenuItems.HEADER + AeroMessages.tr("gui.training.aero_rec") + stepTitle();
    }

    @Override public String permission() { return AdminPermissions.TRAINING_RECORD; }

    @Override protected int rows() { return 6; }

    /** The wizard holds an operator's half-finished choices; a redraw under their hands is wrong. */
    @Override public boolean live() { return false; }

    private RecordingDraft draft() { return service().draft(viewer.getUniqueId()); }

    @Override protected void draw(Inventory inventory) {
        actions.clear();
        for (int i = 0; i < 54; i++) actions.add(null);
        RecordingDraft draft = draft();
        inventory.setItem(4, progressItem(draft));

        switch (step) {
            case PLAYER -> drawPlayers(inventory);
            case LABEL -> drawLabels(inventory, draft);
            case CHEAT_FAMILY -> drawPresets(inventory, service().config().training().cheatFamilies(),
                    "cheat family", draft.cheatFamily(), value -> draft.cheatFamily(value), false);
            case CLIENT -> drawPresets(inventory, service().config().training().clientFamilies(),
                    "client", draft.clientFamily(), value -> draft.clientFamily(value), true);
            case CONFIGURATION -> drawPresets(inventory, service().config().training().configurations(),
                    "configuration", draft.configuration(), value -> draft.configuration(value), true);
            case ASSIST -> drawAssist(inventory, draft);
            case SCENARIO -> drawPresets(inventory, service().config().training().scenarios(),
                    "scenario", draft.scenario(), value -> draft.scenario(value), true);
            case CONFIRM -> drawConfirm(inventory, draft);
        }
        footer(inventory, this::back, AeroMessages.tr("gui.training.the_previous_step"));
    }

    /** Which numbered step this is, and how many there are for the label being recorded. */
    private int stepNumber() {
        List<Step> order = stepOrder();
        int index = order.indexOf(step);
        return index < 0 ? 1 : index + 1;
    }

    private int stepCount() { return stepOrder().size(); }

    /**
     * The steps this recording will actually walk, in order.
     *
     * <p>Only CHEAT is asked for a family and a strength, so the count an operator sees matches the
     * screens they will actually be shown rather than every screen the wizard can draw.
     */
    private List<Step> stepOrder() {
        DatasetMetadata.Label label = draft().label();
        List<Step> order = new ArrayList<>();
        order.add(Step.PLAYER);
        order.add(Step.LABEL);
        if (label == DatasetMetadata.Label.CHEAT) order.add(Step.CHEAT_FAMILY);
        order.add(Step.CLIENT);
        order.add(Step.CONFIGURATION);
        if (label == DatasetMetadata.Label.CHEAT) order.add(Step.ASSIST);
        order.add(Step.SCENARIO);
        order.add(Step.CONFIRM);
        return order;
    }

    /** The step's name in the operator's language; {@link Step#title()} stays the internal one. */
    private String stepTitle() {
        return switch (step) {
            case PLAYER -> AeroMessages.tr("gui.wizard.title.player");
            case LABEL -> AeroMessages.tr("gui.wizard.title.label");
            case CHEAT_FAMILY -> AeroMessages.tr("gui.wizard.title.cheat_family");
            case CLIENT -> AeroMessages.tr("gui.wizard.title.client");
            case CONFIGURATION -> AeroMessages.tr("gui.wizard.title.configuration");
            case ASSIST -> AeroMessages.tr("gui.wizard.title.assist");
            case SCENARIO -> AeroMessages.tr("gui.wizard.title.scenario");
            case CONFIRM -> AeroMessages.tr("gui.wizard.title.confirm");
        };
    }

    /**
     * One sentence saying what this screen wants, because a title alone does not say it.
     *
     * <p>Spelled out per step rather than assembled from the step name: a key built at runtime is
     * one the catalog audit cannot verify, and a missing one would reach an operator as its own key.
     */
    private String hint() {
        return switch (step) {
            case PLAYER -> AeroMessages.tr("gui.wizard.hint.player");
            case LABEL -> AeroMessages.tr("gui.wizard.hint.label");
            case CHEAT_FAMILY -> AeroMessages.tr("gui.wizard.hint.cheat_family");
            case CLIENT -> AeroMessages.tr("gui.wizard.hint.client");
            case CONFIGURATION -> AeroMessages.tr("gui.wizard.hint.configuration");
            case ASSIST -> AeroMessages.tr("gui.wizard.hint.assist");
            case SCENARIO -> AeroMessages.tr("gui.wizard.hint.scenario");
            case CONFIRM -> AeroMessages.tr("gui.wizard.hint.confirm");
        };
    }

    private ItemStack progressItem(RecordingDraft draft) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.WARN + AeroMessages.tr("gui.wizard.step") + " " + stepNumber()
                + MenuItems.MUTED + " / " + stepCount() + MenuItems.LABEL + "  —  " + stepTitle());
        lore.addAll(MenuItems.wrap(MenuItems.VALUE, hint(), 44));
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.player"), draft.targetName() == null ? "-" : draft.targetName()));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.label"), draft.label() == null ? "-" : AdminLabels.datasetLabel(draft.label()),
                MenuItems.colourOf(draft.label())));
        if (draft.label() == DatasetMetadata.Label.CHEAT) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.cheat_family"), blank(draft.cheatFamily())));
        }
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.client"), blank(draft.clientFamily())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.configuration"), blank(draft.configuration())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.assist"), draft.assistStrength() == null ? "-"
                : AdminLabels.assistStrength(draft.assistStrength())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.scenario"), blank(draft.scenario())));
        return MenuItems.item(Material.WRITABLE_BOOK, MenuItems.HEADER + AeroMessages.tr("gui.training.draft"), lore);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * Whether the recorder will actually accept a session right now.
     *
     * <p>Both switches have to be on. Without them the wizard still walks, the metadata still
     * validates, and the recorder refuses on the very last click — which is the worst possible
     * moment to learn it, with a test subject already standing there waiting.
     */
    private boolean recordingEnabled() {
        var config = gui.neural().config();
        return config != null && config.recordingEnabled();
    }

    /** Says, where the operator can act on it, that nothing will be recorded until this is fixed. */
    private void recordingWarning(Inventory inventory, int slot) {
        if (recordingEnabled()) return;
        List<String> lore = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.wizard.recording_off_detail"), 44));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED, AeroMessages.tr("gui.wizard.recording_off_where"), 44));
        inventory.setItem(slot, MenuItems.item(Material.BARRIER,
                MenuItems.BAD + AeroMessages.tr("gui.wizard.recording_off"), lore));
    }

    private void drawPlayers(Inventory inventory) {
        recordingWarning(inventory, 8);
        var selected = dev.aeroac.neural.admin.AdminSnapshot.page(service().snapshot().players(), page, 36);
        page = selected.index();
        pagination(inventory, selected.hasPrevious(), selected.hasNext());
        int slot = 9;
        for (AdminPlayerView view : selected.items()) {
            boolean recording = view.isRecording();
            inventory.setItem(slot, MenuItems.head(view.uuid(), view.name(),
                    (recording ? MenuItems.MUTED : MenuItems.LABEL) + view.name(),
                    recording
                            ? List.of(MenuItems.line(AeroMessages.tr("gui.training.already_recording"),
                                    view.recording().shortLabel(), MenuItems.WARN),
                                    MenuItems.note(AeroMessages.tr("gui.training.stop_that_session_before_starting_another")))
                            : List.of(MenuItems.line(AeroMessages.tr("gui.training.ping"), view.ping() < 0 ? AdminStyle.NO_DATA
                                            : view.ping() + "ms"),
                                    "", MenuItems.note(AeroMessages.tr("gui.training.click_to_record_this_player")))));
            if (!recording) {
                actions.set(slot, () -> {
                    draft().target(view.uuid(), view.name());
                    gui.show(new WizardMenu(gui, viewer, Step.LABEL));
                });
            }
            slot++;
        }
        if (slot == 9) {
            inventory.setItem(22, MenuItems.item(Material.BARRIER, MenuItems.MUTED + AeroMessages.tr("gui.training.nobody_online")));
        }
    }

    private void drawLabels(Inventory inventory, RecordingDraft draft) {
        put(inventory, 20, MenuItems.item(Material.LIME_CONCRETE, MenuItems.GOOD + "LEGIT",
                        MenuItems.note(AeroMessages.tr("gui.training.honest_play_recorded_deliberately")),
                        MenuItems.note(AeroMessages.tr("gui.training.assist_strength_is_always_none"))),
                () -> {
                    draft.label(DatasetMetadata.Label.LEGIT);
                    gui.show(new WizardMenu(gui, viewer, Step.CLIENT));
                });
        put(inventory, 22, MenuItems.item(Material.RED_CONCRETE, MenuItems.BAD + "CHEAT",
                        MenuItems.note(AeroMessages.tr("gui.training.a_cheat_client_configured_on_purpose")),
                        MenuItems.note(AeroMessages.tr("gui.training.needs_a_family_and_cannot_be_none"))),
                () -> {
                    draft.label(DatasetMetadata.Label.CHEAT);
                    gui.show(new WizardMenu(gui, viewer, Step.CHEAT_FAMILY));
                });
        put(inventory, 24, MenuItems.item(Material.YELLOW_CONCRETE, MenuItems.WARN + "UNLABELED",
                        MenuItems.note(AeroMessages.tr("gui.training.production_capture_no_claim_either_way")),
                        MenuItems.note(AeroMessages.tr("gui.training.never_used_as_training_ground_truth"))),
                () -> {
                    draft.label(DatasetMetadata.Label.UNLABELED);
                    gui.show(new WizardMenu(gui, viewer, Step.CLIENT));
                });
    }

    private void drawPresets(Inventory inventory, List<String> presets, String field, String currentValue,
                             java.util.function.Consumer<String> setter, boolean skippable) {
        var selectedPage = dev.aeroac.neural.admin.AdminSnapshot.page(presets, page, 36);
        page = selectedPage.index();
        pagination(inventory, selectedPage.hasPrevious(), selectedPage.hasNext());
        int slot = 9;
        for (String preset : selectedPage.items()) {
            boolean selected = preset.equalsIgnoreCase(currentValue);
            put(inventory, slot, MenuItems.item(selected ? Material.LIME_STAINED_GLASS_PANE : Material.PAPER,
                            (selected ? MenuItems.GOOD : MenuItems.LABEL) + preset,
                            selected ? MenuItems.GOOD + AeroMessages.tr("gui.training.selected") : MenuItems.note(AeroMessages.tr("gui.training.click_to_choose"))),
                    () -> {
                        setter.accept(preset);
                        gui.show(new WizardMenu(gui, viewer, next()));
                    });
            slot++;
        }
        if (selectedPage.total() == 0) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    MenuItems.MUTED + AeroMessages.tr("gui.wizard.no_presets"),
                    MenuItems.wrap(MenuItems.MUTED, AeroMessages.tr("gui.wizard.no_presets_detail"), 44)));
        }

        put(inventory, 47, MenuItems.item(Material.NAME_TAG, MenuItems.HEADER + AeroMessages.tr("gui.training.type_a_value"),
                        MenuItems.line(AeroMessages.tr("gui.training.current"), blank(currentValue)),
                        "",
                        MenuItems.note(AeroMessages.tr("gui.wizard.type_a_value_detail")),
                        MenuItems.note(AeroMessages.tr("gui.training.use_it_when_this_list_has_no_right_answer"))),
                () -> suggest(field));

        boolean chosen = currentValue != null && !currentValue.isBlank();
        // The forward button is always in the same slot, so an operator learns one place to look.
        // What it does depends on what they have picked; what it will not do is disappear.
        if (chosen) {
            put(inventory, 49, MenuItems.item(Material.LIME_CONCRETE, MenuItems.GOOD + AeroMessages.tr("gui.training.continue"),
                            MenuItems.line(AeroMessages.tr("gui.training.selected"), currentValue)),
                    () -> gui.show(new WizardMenu(gui, viewer, next())));
        } else if (skippable) {
            put(inventory, 49, MenuItems.item(Material.LIME_STAINED_GLASS_PANE,
                            MenuItems.GOOD + AeroMessages.tr("gui.wizard.skip_and_continue"),
                            MenuItems.note(AeroMessages.tr("gui.training.recorded_as_unset_rather_than_guessed"))),
                    () -> {
                        setter.accept("");
                        gui.show(new WizardMenu(gui, viewer, next()));
                    });
        } else {
            inventory.setItem(49, MenuItems.item(Material.BARRIER,
                    MenuItems.MUTED + AeroMessages.tr("gui.wizard.choose_first"),
                    MenuItems.wrap(MenuItems.MUTED, AeroMessages.tr("gui.wizard.choose_first_detail"), 44)));
        }
        if (skippable && chosen) {
            put(inventory, 51, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, MenuItems.MUTED + AeroMessages.tr("gui.training.leave_unset"),
                            MenuItems.note(AeroMessages.tr("gui.training.recorded_as_unset_rather_than_guessed"))),
                    () -> {
                        setter.accept("");
                        gui.show(new WizardMenu(gui, viewer, next()));
                    });
        }
    }

    private void drawAssist(Inventory inventory, RecordingDraft draft) {
        DatasetMetadata.AssistStrength[] options = draft.selectableStrengths();
        int slot = 19;
        for (DatasetMetadata.AssistStrength strength : options) {
            boolean selected = strength == draft.assistStrength();
            List<String> lore = new ArrayList<>();
            lore.addAll(MenuItems.wrap(MenuItems.MUTED, describe(strength), 40));
            lore.add("");
            lore.add(selected ? MenuItems.GOOD + AeroMessages.tr("gui.training.selected") : MenuItems.note(AeroMessages.tr("gui.training.click_to_choose")));
            put(inventory, slot, MenuItems.item(materialFor(strength),
                    (selected ? MenuItems.GOOD : MenuItems.LABEL) + AdminLabels.assistStrength(strength), lore), () -> {
                draft.assistStrength(strength);
                gui.show(new WizardMenu(gui, viewer, Step.SCENARIO));
            });
            slot++;
        }
        List<String> rule = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED,
                draft.label() == DatasetMetadata.Label.LEGIT
                        ? AeroMessages.tr("gui.training.legit_is_honest_play_so_none_is_the_only_valid_strength_and")
                        : AeroMessages.tr("gui.training.none_is_not_offered_a_cheat_session_that_claims_no_assistanc")
                                + AeroMessages.tr("gui.training.counted_as_a_cheat_nobody_could_detect_unknown_is_the_honest")
                                + AeroMessages.tr("gui.training.when_the_strength_was_not_written_down"), 44));
        inventory.setItem(40, MenuItems.item(Material.BOOK, MenuItems.HEADER + AeroMessages.tr("gui.training.why_these"), rule));
    }

    private static Material materialFor(DatasetMetadata.AssistStrength strength) {
        return switch (strength) {
            case NONE -> Material.LIME_STAINED_GLASS_PANE;
            case VERY_LOW -> Material.BONE_MEAL;
            case LOW -> Material.YELLOW_STAINED_GLASS_PANE;
            case MEDIUM -> Material.ORANGE_STAINED_GLASS_PANE;
            case HIGH -> Material.RED_STAINED_GLASS_PANE;
            case UNKNOWN -> Material.GRAY_STAINED_GLASS_PANE;
        };
    }

    private static String describe(DatasetMetadata.AssistStrength strength) {
        return switch (strength) {
            case NONE -> "No assistance at all.";
            case VERY_LOW -> "Barely on. The hardest case to detect and the most valuable to record.";
            case LOW -> "Subtle help, usable in a real fight without being obvious.";
            case MEDIUM -> "Clearly helping.";
            case HIGH -> "Obvious. Easy to catch, and not what a detector is judged on.";
            case UNKNOWN -> "The strength was not recorded. Never treated as NONE.";
        };
    }

    private void drawConfirm(Inventory inventory, RecordingDraft draft) {
        confirmed = draft.copy();
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.player"), blank(draft.targetName())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.label"), draft.label() == null ? "-" : AdminLabels.datasetLabel(draft.label()),
                MenuItems.colourOf(draft.label())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.cheat_family"), blank(draft.cheatFamily())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.client"), blank(draft.clientFamily())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.configuration"), blank(draft.configuration())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.assist"), draft.assistArgument()));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.scenario"), blank(draft.scenario())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.notes"), blank(draft.notes())));
        inventory.setItem(22, MenuItems.item(Material.PAPER, MenuItems.HEADER + AeroMessages.tr("gui.training.session_to_open"), lore));

        recordingWarning(inventory, 40);
        String problem = draft.problemMessage();
        if (problem == null) {
            put(inventory, SLOT_CONFIRM, MenuItems.item(Material.LIME_CONCRETE,
                            MenuItems.GOOD + AeroMessages.tr("gui.training.start_recording"),
                            MenuItems.note(AeroMessages.tr("gui.training.opens_the_session_and_starts_writing_to_disk"))),
                    this::start);
        } else {
            inventory.setItem(SLOT_CONFIRM, MenuItems.item(Material.BARRIER,
                    MenuItems.BAD + AeroMessages.tr("gui.training.cannot_start"), MenuItems.wrap(MenuItems.MUTED, problem, 44)));
        }
    }

    /** Hands the operator the exact command the {@code /aero training set} handler expects. */
    private void suggest(String field) {
        Sender sender = senderOf();
        String command = "/aero training set " + field.replace(' ', '-') + " ";
        if (sender == null) {
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.training.run") + command + AeroMessages.tr("gui.wizard.value_placeholder"));
        } else {
            sender.sendMessage(Component.text(AeroMessages.tr("gui.training.click_to_fill_in_the") + field + ": ", NamedTextColor.GRAY)
                    .append(Component.text("[" + command.trim() + " ...]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.suggestCommand(command))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text(AeroMessages.tr("gui.training.type_the_value_and_press_enter"), NamedTextColor.GRAY)))));
        }
        viewer.closeInventory();
    }

    /**
     * Opens the session through the one existing entry point.
     *
     * <p>The wizard passes exactly the arguments the command passes. There is no second recording
     * path with its own semantics: the same validation runs, the same metadata is written and the
     * same file lands on disk whichever way an operator started it.
     */
    private void start() {
        if (submitted) return;
        RecordingDraft draft = confirmed;
        if (draft == null) return;
        submitted = true;
        // One start path, shared with the quick screen: same validation, same recorder entry point.
        if (startRecording(draft)) {
            gui.show(new RecordingsMenu(gui, viewer));
        } else {
            submitted = false;
        }
    }

    public static Step resumeStep(String field, RecordingDraft draft) {
        if (draft.targetUuid() == null) return Step.PLAYER;
        if (draft.label() == null) return Step.LABEL;
        return switch (field.toLowerCase(java.util.Locale.ROOT)) {
            case "cheat-family", "family" -> Step.CLIENT;
            case "client", "client-family" -> Step.CONFIGURATION;
            case "configuration", "config" -> draft.label() == DatasetMetadata.Label.CHEAT ? Step.ASSIST : Step.SCENARIO;
            case "scenario", "notes" -> Step.CONFIRM;
            default -> Step.PLAYER;
        };
    }

    private void pagination(Inventory inventory, boolean previous, boolean next) {
        if (previous) put(inventory, 45, MenuItems.item(Material.ARROW, MenuItems.HEADER + AeroMessages.tr("gui.training.previous")),
                () -> { page--; redraw(); });
        if (next) put(inventory, 53, MenuItems.item(Material.ARROW, MenuItems.HEADER + AeroMessages.tr("gui.training.next")),
                () -> { page++; redraw(); });
    }

    /** The next step for the label being recorded; CHEAT is the only one that needs a family. */
    private Step next() {
        return switch (step) {
            case PLAYER -> Step.LABEL;
            case LABEL -> draft().label() == DatasetMetadata.Label.CHEAT ? Step.CHEAT_FAMILY : Step.CLIENT;
            case CHEAT_FAMILY -> Step.CLIENT;
            case CLIENT -> Step.CONFIGURATION;
            // Only CHEAT is asked for a strength. LEGIT is NONE by rule, and an UNLABELED capture
            // has nobody to ask: it is recorded as UNKNOWN, which is the honest answer.
            case CONFIGURATION -> draft().label() == DatasetMetadata.Label.CHEAT ? Step.ASSIST : Step.SCENARIO;
            case ASSIST, SCENARIO -> step == Step.ASSIST ? Step.SCENARIO : Step.CONFIRM;
            case CONFIRM -> Step.CONFIRM;
        };
    }

    private Step previous() {
        return switch (step) {
            case PLAYER, LABEL -> Step.PLAYER;
            case CHEAT_FAMILY -> Step.LABEL;
            case CLIENT -> draft().label() == DatasetMetadata.Label.CHEAT ? Step.CHEAT_FAMILY : Step.LABEL;
            case CONFIGURATION -> Step.CLIENT;
            case ASSIST -> Step.CONFIGURATION;
            case SCENARIO -> draft().label() == DatasetMetadata.Label.CHEAT ? Step.ASSIST : Step.CONFIGURATION;
            case CONFIRM -> Step.SCENARIO;
        };
    }

    private void put(Inventory inventory, int slot, ItemStack item, Runnable action) {
        inventory.setItem(slot, item);
        actions.set(slot, action);
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
        if (slot < 0 || slot >= actions.size()) return;
        Runnable action = actions.get(slot);
        if (action != null) action.run();
    }

    private void back() {
        if (step == Step.PLAYER) {
            service().clearDraft(viewer.getUniqueId());
            gui.show(new TrainingMenu(gui, viewer));
        } else {
            gui.show(new WizardMenu(gui, viewer, previous()));
        }
    }
}
