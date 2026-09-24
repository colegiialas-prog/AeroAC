package ac.grim.grimac.platform.bukkit.admin.training;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminPlayerView;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.admin.training.RecordingDraft;
import ac.grim.grimac.neural.dataset.DatasetMetadata;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.bukkit.admin.AeroMenu;
import ac.grim.grimac.platform.bukkit.admin.BukkitAdminGui;
import ac.grim.grimac.platform.bukkit.admin.MenuItems;
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
        return MenuItems.HEADER + "AERO › REC › " + step.title();
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
            case CONFIGURATION -> drawPresets(inventory, List.of(),
                    "configuration", draft.configuration(), value -> draft.configuration(value), true);
            case ASSIST -> drawAssist(inventory, draft);
            case SCENARIO -> drawPresets(inventory, service().config().training().scenarios(),
                    "scenario", draft.scenario(), value -> draft.scenario(value), true);
            case CONFIRM -> drawConfirm(inventory, draft);
        }
        footer(inventory, this::back, "the previous step");
    }

    private ItemStack progressItem(RecordingDraft draft) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line("Player", draft.targetName() == null ? "-" : draft.targetName()));
        lore.add(MenuItems.line("Label", draft.label() == null ? "-" : draft.label().name(),
                MenuItems.colourOf(draft.label())));
        if (draft.label() == DatasetMetadata.Label.CHEAT) {
            lore.add(MenuItems.line("Cheat family", blank(draft.cheatFamily())));
        }
        lore.add(MenuItems.line("Client", blank(draft.clientFamily())));
        lore.add(MenuItems.line("Configuration", blank(draft.configuration())));
        lore.add(MenuItems.line("Assist", draft.assistStrength() == null ? "-"
                : draft.assistStrength().name()));
        lore.add(MenuItems.line("Scenario", blank(draft.scenario())));
        return MenuItems.item(Material.WRITABLE_BOOK, MenuItems.HEADER + "DRAFT", lore);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private void drawPlayers(Inventory inventory) {
        var selected = ac.grim.grimac.neural.admin.AdminSnapshot.page(service().snapshot().players(), page, 36);
        page = selected.index();
        pagination(inventory, selected.hasPrevious(), selected.hasNext());
        int slot = 9;
        for (AdminPlayerView view : selected.items()) {
            boolean recording = view.isRecording();
            inventory.setItem(slot, MenuItems.head(view.uuid(), view.name(),
                    (recording ? MenuItems.MUTED : MenuItems.LABEL) + view.name(),
                    recording
                            ? List.of(MenuItems.line("Already recording",
                                    view.recording().shortLabel(), MenuItems.WARN),
                                    MenuItems.note("Stop that session before starting another."))
                            : List.of(MenuItems.line("Ping", view.ping() < 0 ? AdminStyle.NO_DATA
                                            : view.ping() + "ms"),
                                    "", MenuItems.note("Click to record this player."))));
            if (!recording) {
                actions.set(slot, () -> {
                    draft().target(view.uuid(), view.name());
                    gui.show(new WizardMenu(gui, viewer, Step.LABEL));
                });
            }
            slot++;
        }
        if (slot == 9) {
            inventory.setItem(22, MenuItems.item(Material.BARRIER, MenuItems.MUTED + "NOBODY ONLINE"));
        }
    }

    private void drawLabels(Inventory inventory, RecordingDraft draft) {
        put(inventory, 20, MenuItems.item(Material.LIME_CONCRETE, MenuItems.GOOD + "LEGIT",
                        MenuItems.note("Honest play, recorded deliberately."),
                        MenuItems.note("Assist strength is always NONE.")),
                () -> {
                    draft.label(DatasetMetadata.Label.LEGIT);
                    gui.show(new WizardMenu(gui, viewer, Step.CLIENT));
                });
        put(inventory, 22, MenuItems.item(Material.RED_CONCRETE, MenuItems.BAD + "CHEAT",
                        MenuItems.note("A cheat client, configured on purpose."),
                        MenuItems.note("Needs a family, and cannot be NONE.")),
                () -> {
                    draft.label(DatasetMetadata.Label.CHEAT);
                    gui.show(new WizardMenu(gui, viewer, Step.CHEAT_FAMILY));
                });
        put(inventory, 24, MenuItems.item(Material.YELLOW_CONCRETE, MenuItems.WARN + "UNLABELED",
                        MenuItems.note("Production capture, no claim either way."),
                        MenuItems.note("Never used as training ground truth.")),
                () -> {
                    draft.label(DatasetMetadata.Label.UNLABELED);
                    gui.show(new WizardMenu(gui, viewer, Step.CLIENT));
                });
    }

    private void drawPresets(Inventory inventory, List<String> presets, String field, String currentValue,
                             java.util.function.Consumer<String> setter, boolean skippable) {
        var selectedPage = ac.grim.grimac.neural.admin.AdminSnapshot.page(presets, page, 36);
        page = selectedPage.index();
        pagination(inventory, selectedPage.hasPrevious(), selectedPage.hasNext());
        int slot = 9;
        for (String preset : selectedPage.items()) {
            boolean selected = preset.equalsIgnoreCase(currentValue);
            put(inventory, slot, MenuItems.item(selected ? Material.LIME_DYE : Material.PAPER,
                            (selected ? MenuItems.GOOD : MenuItems.LABEL) + preset,
                            selected ? MenuItems.GOOD + "Selected" : MenuItems.note("Click to choose")),
                    () -> {
                        setter.accept(preset);
                        gui.show(new WizardMenu(gui, viewer, next()));
                    });
            slot++;
        }
        put(inventory, 47, MenuItems.item(Material.NAME_TAG, MenuItems.HEADER + "TYPE A VALUE",
                        MenuItems.line("Current", blank(currentValue)),
                        "",
                        MenuItems.note("Sends you a command to complete in chat."),
                        MenuItems.note("Use it when this list has no right answer.")),
                () -> suggest(field));
        if (currentValue != null && !currentValue.isBlank()) {
            put(inventory, 49, MenuItems.item(Material.LIME_DYE, MenuItems.GOOD + "CONTINUE",
                    MenuItems.line("Selected", currentValue)),
                    () -> gui.show(new WizardMenu(gui, viewer, next())));
        }
        if (skippable) {
            put(inventory, 51, MenuItems.item(Material.GRAY_DYE, MenuItems.MUTED + "LEAVE UNSET",
                            MenuItems.note("Recorded as unset rather than guessed.")),
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
            lore.add(selected ? MenuItems.GOOD + "Selected" : MenuItems.note("Click to choose"));
            put(inventory, slot, MenuItems.item(materialFor(strength),
                    (selected ? MenuItems.GOOD : MenuItems.LABEL) + strength.name(), lore), () -> {
                draft.assistStrength(strength);
                gui.show(new WizardMenu(gui, viewer, Step.SCENARIO));
            });
            slot++;
        }
        List<String> rule = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED,
                draft.label() == DatasetMetadata.Label.LEGIT
                        ? "LEGIT is honest play, so NONE is the only valid strength and it is already set."
                        : "NONE is not offered: a CHEAT session that claims no assistance would be "
                                + "counted as a cheat nobody could detect. UNKNOWN is the honest answer "
                                + "when the strength was not written down.", 44));
        inventory.setItem(40, MenuItems.item(Material.BOOK, MenuItems.HEADER + "WHY THESE", rule));
    }

    private static Material materialFor(DatasetMetadata.AssistStrength strength) {
        return switch (strength) {
            case NONE -> Material.LIME_DYE;
            case VERY_LOW -> Material.BONE_MEAL;
            case LOW -> Material.YELLOW_DYE;
            case MEDIUM -> Material.ORANGE_DYE;
            case HIGH -> Material.RED_DYE;
            case UNKNOWN -> Material.GRAY_DYE;
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
        lore.add(MenuItems.line("Player", blank(draft.targetName())));
        lore.add(MenuItems.line("Label", draft.label() == null ? "-" : draft.label().name(),
                MenuItems.colourOf(draft.label())));
        lore.add(MenuItems.line("Cheat family", blank(draft.cheatFamily())));
        lore.add(MenuItems.line("Client", blank(draft.clientFamily())));
        lore.add(MenuItems.line("Configuration", blank(draft.configuration())));
        lore.add(MenuItems.line("Assist", draft.assistArgument()));
        lore.add(MenuItems.line("Scenario", blank(draft.scenario())));
        lore.add(MenuItems.line("Notes", blank(draft.notes())));
        inventory.setItem(22, MenuItems.item(Material.PAPER, MenuItems.HEADER + "SESSION TO OPEN", lore));

        String problem = draft.problem();
        if (problem == null) {
            put(inventory, SLOT_CONFIRM, MenuItems.item(Material.LIME_CONCRETE,
                            MenuItems.GOOD + "START RECORDING",
                            MenuItems.note("Opens the session and starts writing to disk.")),
                    this::start);
        } else {
            inventory.setItem(SLOT_CONFIRM, MenuItems.item(Material.BARRIER,
                    MenuItems.BAD + "CANNOT START", MenuItems.wrap(MenuItems.MUTED, problem, 44)));
        }
    }

    /** Hands the operator the exact command the {@code /aero training set} handler expects. */
    private void suggest(String field) {
        Sender sender = senderOf();
        String command = "/aero training set " + field.replace(' ', '-') + " ";
        if (sender == null) {
            viewer.sendMessage(MenuItems.MUTED + "Run: " + command + "<value>");
        } else {
            sender.sendMessage(Component.text("Click to fill in the " + field + ": ", NamedTextColor.GRAY)
                    .append(Component.text("[" + command.trim() + " ...]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.suggestCommand(command))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("Type the value and press enter", NamedTextColor.GRAY)))));
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
        if (!permitted(AdminPermissions.TRAINING_RECORD)) {
            deny(AdminPermissions.TRAINING_RECORD);
            return;
        }
        RecordingDraft draft = confirmed;
        if (draft == null) return;
        String problem = draft.problem();
        if (problem != null) {
            viewer.sendMessage(MenuItems.BAD + problem);
            return;
        }
        var player = gui.tracked(draft.targetUuid());
        if (player == null) {
            viewer.sendMessage(MenuItems.BAD + "That player is no longer online.");
            return;
        }
        Sender recipient = senderOf();
        long epoch = service().generation();
        submitted = true;
        player.runSafely(() -> {
            if (service().generation() != epoch || player.getNeuralState().disconnected) return;
            gui.neural().startSession(player, draft.label(),
                draft.cheatFamily(), draft.clientFamily(), draft.configuration(), draft.scenario(),
                draft.assistArgument(), draft.notes(), message -> {
                    ac.grim.grimac.neural.NeuralMessages.send(recipient, message);
                    service().invalidateDatasetSummary();
                });
        });
        service().clearDraft(viewer.getUniqueId());
        service().invalidateDatasetSummary();
        gui.show(new RecordingsMenu(gui, viewer));
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
        if (previous) put(inventory, 45, MenuItems.item(Material.ARROW, MenuItems.HEADER + "PREVIOUS"),
                () -> { page--; redraw(); });
        if (next) put(inventory, 53, MenuItems.item(Material.ARROW, MenuItems.HEADER + "NEXT"),
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
