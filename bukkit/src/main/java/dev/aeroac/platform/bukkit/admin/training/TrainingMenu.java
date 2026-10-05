package dev.aeroac.platform.bukkit.admin.training;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminRuntimeControls;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.admin.training.DatasetSummary;
import dev.aeroac.neural.admin.training.TrainingJob;
import dev.aeroac.neural.admin.training.TrainingLaunchers;
import dev.aeroac.neural.admin.training.TrainingStatus;
import dev.aeroac.neural.dataset.DatasetJson;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.platform.bukkit.admin.AeroMenu;
import dev.aeroac.platform.bukkit.admin.BukkitAdminGui;
import dev.aeroac.platform.bukkit.admin.MainMenu;
import dev.aeroac.platform.bukkit.admin.MenuItems;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The training centre: collecting a dataset and watching what is done with it.
 *
 * <p>The whole point of this section is that collecting data should not require remembering command
 * syntax while a test subject waits. Everything here maps onto the recorder and the Python tooling
 * that already exist; nothing here is a second implementation of either.
 */
public final class TrainingMenu extends AeroMenu {
    private static final int SLOT_ACTIVE = 11;
    private static final int SLOT_START = 12;
    private static final int SLOT_OVERVIEW = 13;
    private static final int SLOT_RECENT = 14;
    private static final int SLOT_REVIEW = 15;
    private static final int SLOT_MODEL = 22;
    private static final int SLOT_COVERAGE = 20;
    private static final int SLOT_HEADER = 4;
    private static final int SLOT_GUIDE = 10;
    private static final int SLOT_RECORDING_SWITCH = 19;
    private static final int SLOT_SERVICE = 21;
    private static final int SLOT_START_FLASH = 23;
    private static final int SLOT_START_PRO = 24;
    private static final int SLOT_CANCEL_JOB = 25;
    private static final int SLOT_REFRESH_JOB = 26;
    private static final int SLOT_BOT = 16;

    public TrainingMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.training.aero_training_center"); }

    @Override public String permission() { return AdminPermissions.TRAINING; }

    @Override protected int rows() { return 4; }

    @Override protected void draw(Inventory inventory) {
        DatasetSummary dataset = service().datasetSummary();
        int active = service().snapshot().recordings().size();
        TrainingJob job = service().training().job();

        inventory.setItem(SLOT_HEADER, header(dataset, active));
        inventory.setItem(SLOT_GUIDE, guide(dataset));
        inventory.setItem(SLOT_RECORDING_SWITCH, recordingSwitch());

        inventory.setItem(SLOT_ACTIVE, MenuItems.item(
                active > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.training.active_recordings"),
                MenuItems.line(AeroMessages.tr("gui.training.running_now"), String.valueOf(active),
                        active > 0 ? MenuItems.GOOD : MenuItems.MUTED),
                "",
                MenuItems.note(AeroMessages.tr("gui.training.progress_live_quality_counters_and_stop"))));

        inventory.setItem(SLOT_START, MenuItems.item(Material.LIME_CONCRETE,
                MenuItems.HEADER + AeroMessages.tr("gui.training.start_recording"),
                MenuItems.note(AeroMessages.tr("gui.quick.hub_left")),
                MenuItems.note(AeroMessages.tr("gui.quick.hub_shift")),
                "",
                MenuItems.note(AeroMessages.tr("gui.training.requires") + AdminPermissions.TRAINING_RECORD)));

        inventory.setItem(SLOT_OVERVIEW, MenuItems.item(Material.BOOKSHELF,
                MenuItems.HEADER + AeroMessages.tr("gui.training.dataset_overview"),
                dataset.ready()
                        ? MenuItems.line(AeroMessages.tr("gui.training.sessions"), String.valueOf(dataset.sessions()))
                        : MenuItems.note(AeroMessages.tr("gui.training.loading")),
                MenuItems.note(AeroMessages.tr("gui.training.counts_and_breakdowns_from_session_metadata"))));

        inventory.setItem(SLOT_COVERAGE, MenuItems.item(Material.MAP,
                MenuItems.HEADER + AeroMessages.tr("gui.training.collection_coverage"),
                MenuItems.note(AeroMessages.tr("gui.training.which_categories_are_thin_against_your_own")),
                MenuItems.note(AeroMessages.tr("gui.training.collection_goals_planning_aid_not_a_verdict"))));

        inventory.setItem(SLOT_RECENT, MenuItems.item(Material.CLOCK,
                MenuItems.HEADER + AeroMessages.tr("gui.training.recent_sessions"),
                MenuItems.note(AeroMessages.tr("gui.training.the_newest_recordings_and_how_they_closed"))));

        inventory.setItem(SLOT_REVIEW, MenuItems.item(
                dataset.ready() && !dataset.attention().isEmpty()
                        ? Material.ORANGE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.training.review_queue"),
                dataset.ready()
                        ? MenuItems.line(AeroMessages.tr("gui.training.needing_a_look"), String.valueOf(dataset.attention().size()),
                                dataset.attention().isEmpty() ? MenuItems.GOOD : MenuItems.WARN)
                        : MenuItems.note(AeroMessages.tr("gui.training.loading")),
                "",
                MenuItems.note(AeroMessages.tr("gui.training.imported_review_verdicts_and_sessions_that")),
                MenuItems.note(AeroMessages.tr("gui.training.did_not_close_cleanly"))));

        inventory.setItem(SLOT_MODEL, MenuItems.item(Material.BEACON,
                MenuItems.HEADER + AeroMessages.tr("gui.training.model_training"),
                MenuItems.line(AeroMessages.tr("gui.training.status"), TrainingStatus.label(job.status()),
                        MenuItems.colourOf(TrainingStatus.tone(job.status()))),
                "",
                MenuItems.note(AeroMessages.tr("gui.training.trains_in_plugin")),
                MenuItems.note(AeroMessages.tr("gui.training.switch_with_models_use"))));

        inventory.setItem(SLOT_SERVICE, serviceBlock(job));
        inventory.setItem(SLOT_START_FLASH, startItem(TrainingLaunchers.PRESET_FLASH, job));
        inventory.setItem(SLOT_START_PRO, startItem(TrainingLaunchers.PRESET_PRO, job));
        inventory.setItem(SLOT_CANCEL_JOB, cancelItem(job));
        inventory.setItem(SLOT_REFRESH_JOB, MenuItems.item(Material.CLOCK,
                MenuItems.HEADER + AeroMessages.tr("gui.training.refresh_job"),
                MenuItems.note(AeroMessages.tr("gui.training.asks_the_backend_for_a_new_snapshot")),
                MenuItems.note(AeroMessages.tr("gui.training.returns_immediately_nothing_waits_on_the_network"))));

        inventory.setItem(SLOT_BOT, MenuItems.item(Material.ZOMBIE_HEAD, MenuItems.HEADER + AeroMessages.tr("gui.bot.title"),
                MenuItems.note(AeroMessages.tr("gui.bot.about1")), MenuItems.note(AeroMessages.tr("gui.bot.about2"))));
        footer(inventory, this::back, AeroMessages.tr("gui.training.the_aero_menu"));
        lockSlot(inventory, SLOT_BOT, AdminPermissions.TRAINING_RECORD);
        lockSlot(inventory, SLOT_START, AdminPermissions.TRAINING_RECORD);
        lockSlot(inventory, SLOT_OVERVIEW, AdminPermissions.TRAINING_OVERVIEW);
        lockSlot(inventory, SLOT_RECENT, AdminPermissions.TRAINING_OVERVIEW);
        lockSlot(inventory, SLOT_COVERAGE, AdminPermissions.TRAINING_OVERVIEW);
        lockSlot(inventory, SLOT_REVIEW, AdminPermissions.TRAINING_REVIEW);
        lockSlot(inventory, SLOT_MODEL, AdminPermissions.TRAINING_MODEL);
    }

    private boolean recordingEnabled() {
        var neural = gui.neural().config();
        return neural != null && neural.recordingEnabled();
    }

    /**
     * The three things that have to happen, in order, with the current state of each.
     *
     * <p>Every other card on this screen answers a question an operator already knew to ask. This
     * one answers the question they actually arrive with — "how do I train a model?" — and it
     * answers it with this server's real state, so a step that is already done says so and the one
     * that is blocking says what to do about it.
     */
    private ItemStack guide(DatasetSummary dataset) {
        boolean recording = recordingEnabled();
        boolean haveData = dataset.ready() && dataset.legit() > 0 && dataset.cheat() > 0;
        boolean service = TrainingLaunchers.available();

        List<String> lore = new ArrayList<>();
        lore.addAll(MenuItems.wrap(MenuItems.MUTED, AeroMessages.tr("gui.guide.intro"), 44));
        lore.add("");

        lore.add(mark(recording) + AeroMessages.tr("gui.guide.step1"));
        lore.add(recording
                ? MenuItems.note(AeroMessages.tr("gui.guide.step1_done"))
                : MenuItems.WARN + "  " + AeroMessages.tr("gui.guide.step1_todo"));
        lore.add("");

        lore.add(mark(haveData) + AeroMessages.tr("gui.guide.step2"));
        if (!dataset.ready()) {
            lore.add(MenuItems.note("  " + AeroMessages.tr("gui.training.loading")));
        } else {
            lore.add(MenuItems.note("  "
                    + AdminLabels.datasetLabel(DatasetMetadata.Label.LEGIT) + " " + dataset.legit()
                    + "   " + AdminLabels.datasetLabel(DatasetMetadata.Label.CHEAT) + " " + dataset.cheat()
                    + "   " + AdminLabels.datasetLabel(DatasetMetadata.Label.UNLABELED) + " " + dataset.unlabeled()));
            lore.add(haveData
                    ? MenuItems.note("  " + AeroMessages.tr("gui.guide.step2_done"))
                    : MenuItems.WARN + "  " + AeroMessages.tr("gui.guide.step2_todo"));
        }
        lore.add("");

        lore.add(mark(service) + AeroMessages.tr("gui.guide.step3"));
        lore.addAll(MenuItems.wrap(MenuItems.MUTED, service
                ? AeroMessages.tr("gui.guide.step3_service")
                : AeroMessages.tr("gui.guide.step3_offline"), 44));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED, AeroMessages.tr("gui.guide.never_automatic"), 44));

        return MenuItems.item(Material.COMPASS, MenuItems.HEADER + AeroMessages.tr("gui.guide.title"), lore);
    }

    private static String mark(boolean done) {
        return (done ? MenuItems.GOOD + "✔ " : MenuItems.BAD + "✘ ");
    }

    /**
     * The switch for step one, on the screen where step one is written down.
     *
     * <p>Same control the status screen offers and the same confirmation: this is not a second way
     * to enable anything, it is the same way, put where an operator is standing when they need it.
     */
    private ItemStack recordingSwitch() {
        boolean recording = recordingEnabled();
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.runtime.state"),
                AeroMessages.tr(recording ? "gui.runtime.state_enabled" : "gui.runtime.state_disabled"),
                recording ? MenuItems.GOOD : MenuItems.MUTED));
        if (!AdminRuntimeControls.available()) {
            lore.add(MenuItems.note(AeroMessages.tr("gui.runtime.unavailable")));
        } else {
            lore.add(MenuItems.note(AeroMessages.tr(recording
                    ? "gui.runtime.disable_note" : "gui.runtime.enable_note")));
        }
        String detail = AdminRuntimeControls.stateDetail();
        if (detail != null && !detail.isBlank()) lore.add(MenuItems.note(detail));
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.runtime.async_note")));
        lore.add(MenuItems.note(AeroMessages.tr("gui.requires") + AdminPermissions.ADMIN));
        return MenuItems.item(recording ? Material.REDSTONE_TORCH : Material.REDSTONE_BLOCK,
                MenuItems.HEADER + AeroMessages.tr(recording
                        ? "gui.guide.recording_disable" : "gui.guide.recording_enable"), lore);
    }

    /** Asks for recording to be switched, through the one control that owns that decision. */
    private void requestRecording(boolean enable) {
        if (!permitted(AdminPermissions.ADMIN)) {
            deny(AdminPermissions.ADMIN);
            return;
        }
        if (!AdminRuntimeControls.available()) {
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.runtime.unavailable"));
            return;
        }
        confirm("gui.runtime.title", enable ? "gui.runtime.confirm_enable" : "gui.runtime.confirm_disable",
                new Object[0], enable ? "gui.runtime.enable" : "gui.runtime.disable",
                List.of(MenuItems.note(AeroMessages.tr("gui.runtime.async_note"))),
                () -> {
                    boolean asked = enable
                            ? AdminRuntimeControls.requestEnable(this::reportRecording)
                            : AdminRuntimeControls.requestDisable(this::reportRecording);
                    if (!asked) viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.runtime.unavailable"));
                },
                () -> new TrainingMenu(gui, viewer));
    }

    private void reportRecording(String message) {
        onMain(() -> {
            if (message != null && !message.isBlank()) {
                viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.runtime.reply") + message);
            }
            if (gui.current(viewer.getUniqueId()) instanceof TrainingMenu) gui.show(new TrainingMenu(gui, viewer));
        });
    }

    private org.bukkit.inventory.ItemStack header(DatasetSummary dataset, int active) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.active_recordings"), String.valueOf(active)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.dataset_version"), DatasetJson.DATASET_VERSION));
        if (!permitted(AdminPermissions.TRAINING_OVERVIEW)) {
            lore.add(MenuItems.note(AeroMessages.tr("gui.training.dataset_totals_require") + AdminPermissions.TRAINING_OVERVIEW));
        } else if (dataset.ready()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.sessions"), String.valueOf(dataset.sessions())));
            lore.add(MenuItems.line("LEGIT", String.valueOf(dataset.legit()), MenuItems.GOOD));
            lore.add(MenuItems.line("CHEAT", String.valueOf(dataset.cheat()), MenuItems.BAD));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.attack_windows"), dataset.audit().attackWindows() < 0
                    ? AdminStyle.NO_DATA : String.valueOf(dataset.audit().attackWindows())));
            lore.add("");
            lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                    AeroMessages.tr("gui.training.session_metadata_records_frames_not_how_many_attack_windows")
                            + AeroMessages.tr("gui.training.from_them_the_offline_audit_counts_those"), 44));
            if (dataset.truncated()) {
                lore.add(MenuItems.note(AeroMessages.tr("gui.training.only_the_newest_sessions_were_read")));
            }
        } else {
            lore.add(MenuItems.note(dataset.failedToLoad()
                    ? AeroMessages.tr("gui.training.summary_unavailable") + dataset.error()
                    : AeroMessages.tr("gui.training.reading_session_metadata_in_the_background")));
        }
        return MenuItems.item(Material.NETHER_STAR, MenuItems.HEADER + AeroMessages.tr("gui.training.training_center"), lore);
    }

    /**
     * The state of the training service and of its job, entirely from the client's cache.
     *
     * <p>Every line is a field the job snapshot actually carries. A field the snapshot does not have
     * renders as NO DATA, and a service that is not connected says so once instead of filling the
     * screen with placeholders. Nothing here calls anybody: the client's getters answer from what
     * arrived last, and asking for a fresher answer is the refresh button's job.
     */
    private ItemStack serviceBlock(TrainingJob job) {
        List<String> lore = new ArrayList<>();
        boolean connected = TrainingLaunchers.available();
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.service_state"),
                AeroMessages.tr(connected ? "gui.training.service_connected" : "gui.training.service_not_connected"),
                connected ? MenuItems.GOOD : MenuItems.MUTED));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.status"), TrainingStatus.label(job.status()),
                MenuItems.colourOf(TrainingStatus.tone(job.status()))));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.dataset"),
                AdminStyle.blankIfEmpty(TrainingLaunchers.dataset(), AdminStyle.NO_DATA)));
        if (job.configured()) {
            lore.add("");
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.job"), AdminStyle.blankIfEmpty(job.jobId(), "-")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.model"), AdminStyle.blankIfEmpty(job.modelType(), "-")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.dataset_version"), AdminStyle.blankIfEmpty(job.datasetVersion(), "-")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.window"), AdminStyle.blankIfEmpty(job.window(), "-")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.heads"), AdminStyle.blankIfEmpty(job.heads(), "-")));
            if (job.totalEpochs() > 0) {
                lore.add(MenuItems.line(AeroMessages.tr("gui.training.epoch"), job.epoch() + " / " + job.totalEpochs()));
            }
            String percent = Double.isNaN(job.progress())
                    ? AdminStyle.NO_DATA : Math.round(job.progress() * 100) + "%";
            lore.add(MenuItems.LABEL + AeroMessages.tr("gui.training.progress") + MenuItems.VALUE + percent);
            if (!Double.isNaN(job.progress())) lore.add(MenuItems.GOOD + AdminStyle.bar(job.progress(), 20));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.train_loss"), AdminStyle.number(job.trainLoss(), 4)));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.validation_loss"), AdminStyle.number(job.validationLoss(), 4)));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.elapsed"), AdminStyle.duration(job.elapsedSeconds())));
        } else {
            String reason = service().training().unavailableReason();
            if (reason != null) {
                lore.add("");
                lore.addAll(MenuItems.wrap(MenuItems.MUTED, reason, 44));
            }
        }
        if (job.message() != null) {
            lore.add("");
            lore.addAll(MenuItems.wrap(MenuItems.MUTED, job.message(), 44));
        }
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.training.nothing_here_promotes_a_model")));
        return MenuItems.item(connected ? Material.LECTERN : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.training.service_block"), lore);
    }

    private ItemStack startItem(String preset, TrainingJob job) {
        boolean pro = TrainingLaunchers.PRESET_PRO.equals(preset);
        boolean ready = TrainingLaunchers.available() && job.configured() && !job.running();
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.preset"), preset));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.dataset"),
                AdminStyle.blankIfEmpty(TrainingLaunchers.dataset(), AdminStyle.NO_DATA)));
        if (!TrainingLaunchers.available()) {
            lore.add(MenuItems.note(AeroMessages.tr("gui.training.launcher_unavailable")));
        } else if (!job.configured()) {
            lore.add(MenuItems.note(AeroMessages.tr("gui.training.service_not_connected_note")));
        } else if (job.running()) {
            lore.add(MenuItems.note(AeroMessages.tr("gui.training.job_already_running")));
        } else {
            lore.add(MenuItems.note(AeroMessages.tr("gui.training.start_note")));
        }
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.training.requires") + AdminPermissions.TRAINING_MODEL));
        return MenuItems.item(ready ? (pro ? Material.DIAMOND : Material.EMERALD) : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr(pro ? "gui.training.start_pro" : "gui.training.start_flash"), lore);
    }

    private ItemStack cancelItem(TrainingJob job) {
        boolean cancellable = TrainingLaunchers.available() && job.running();
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.status"), TrainingStatus.label(job.status()),
                MenuItems.colourOf(TrainingStatus.tone(job.status()))));
        lore.add(MenuItems.note(AeroMessages.tr("gui.training.cancel_note")));
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.training.requires") + AdminPermissions.TRAINING_MODEL));
        return MenuItems.item(cancellable ? Material.RED_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.training.cancel_job"), lore);
    }

    /** Asks for a run; the click only asks, the launcher does the waiting. */
    private void requestStart(String preset) {
        if (!permitted(AdminPermissions.TRAINING_MODEL)) {
            deny(AdminPermissions.TRAINING_MODEL);
            return;
        }
        if (!TrainingLaunchers.available()) {
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.training.launcher_unavailable"));
            return;
        }
        boolean pro = TrainingLaunchers.PRESET_PRO.equals(preset);
        confirm("gui.training.confirm_title", "gui.training.confirm_start",
                new Object[] { preset, AdminStyle.blankIfEmpty(TrainingLaunchers.dataset(), "-") },
                pro ? "gui.training.start_pro" : "gui.training.start_flash",
                List.of(MenuItems.note(AeroMessages.tr("gui.training.confirm_start_note"))),
                () -> {
                    if (!TrainingLaunchers.start(preset, this::report)) {
                        viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.training.launcher_unavailable"));
                    }
                },
                () -> new TrainingMenu(gui, viewer));
    }

    /** Asks for the running job to stop; cancelling is as asynchronous as starting. */
    private void requestCancel() {
        if (!permitted(AdminPermissions.TRAINING_MODEL)) {
            deny(AdminPermissions.TRAINING_MODEL);
            return;
        }
        if (!TrainingLaunchers.available()) {
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.training.launcher_unavailable"));
            return;
        }
        confirm("gui.training.confirm_title", "gui.training.confirm_cancel", new Object[0],
                "gui.training.cancel_job",
                List.of(MenuItems.note(AeroMessages.tr("gui.training.cancel_note"))),
                () -> {
                    if (!TrainingLaunchers.cancel(this::report)) {
                        viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.training.launcher_unavailable"));
                    }
                },
                () -> new TrainingMenu(gui, viewer));
    }

    /**
     * Shows the service's answer to a request.
     *
     * <p>Arrives on the service's thread, so the message and any redraw are queued onto the main
     * thread first. The screen is only rebuilt when this operator is still looking at it: a reply
     * from a job nobody is watching must not pull an administrator out of the screen they moved to.
     */
    private void report(String message) {
        onMain(() -> {
            if (message != null && !message.isBlank()) {
                viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.training.service_reply") + message);
            }
            if (gui.current(viewer.getUniqueId()) instanceof TrainingMenu) {
                gui.show(new TrainingMenu(gui, viewer));
            }
        });
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
        switch (slot) {
            case SLOT_ACTIVE -> gui.show(new RecordingsMenu(gui, viewer));
            case SLOT_BOT -> {
                if (permitted(AdminPermissions.TRAINING_RECORD)) gui.show(new dev.aeroac.platform.bukkit.admin.bot.BotMenu(gui, viewer));
                else deny(AdminPermissions.TRAINING_RECORD);
            }
            case SLOT_START -> {
                if (!permitted(AdminPermissions.TRAINING_RECORD)) {
                    deny(AdminPermissions.TRAINING_RECORD);
                } else {
                    service().clearDraft(viewer.getUniqueId());
                    // Plain click is the fast path; the full form is there for whoever wants it.
                    gui.show(event.isShiftClick()
                            ? new WizardMenu(gui, viewer, WizardMenu.Step.PLAYER)
                            : new QuickRecordMenu(gui, viewer));
                }
            }
            case SLOT_OVERVIEW -> open(DatasetMenu.Kind.OVERVIEW);
            case SLOT_COVERAGE -> open(DatasetMenu.Kind.COVERAGE);
            case SLOT_RECENT -> open(DatasetMenu.Kind.RECENT);
            case SLOT_REVIEW -> open(DatasetMenu.Kind.REVIEW);
            case SLOT_MODEL -> {
                if (permitted(AdminPermissions.TRAINING_MODEL)) gui.show(new ModelMenu(gui, viewer, false));
                else deny(AdminPermissions.TRAINING_MODEL);
            }
            case SLOT_RECORDING_SWITCH -> requestRecording(!recordingEnabled());
            case SLOT_START_FLASH -> requestStart(TrainingLaunchers.PRESET_FLASH);
            case SLOT_START_PRO -> requestStart(TrainingLaunchers.PRESET_PRO);
            case SLOT_CANCEL_JOB -> requestCancel();
            case SLOT_REFRESH_JOB -> service().training().poll();
            default -> { }
        }
    }

    private void open(DatasetMenu.Kind kind) {
        String needed = kind == DatasetMenu.Kind.REVIEW
                ? AdminPermissions.TRAINING_REVIEW : AdminPermissions.TRAINING_OVERVIEW;
        if (permitted(needed)) gui.show(new DatasetMenu(gui, viewer, kind, 0));
        else deny(needed);
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
