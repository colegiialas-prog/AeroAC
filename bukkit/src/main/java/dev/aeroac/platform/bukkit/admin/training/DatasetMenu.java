package dev.aeroac.platform.bukkit.admin.training;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminPermissions;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.neural.admin.AdminSnapshot;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.admin.training.CoverageReport;
import dev.aeroac.neural.admin.training.DatasetSummary;
import dev.aeroac.neural.admin.training.SessionRecord;
import dev.aeroac.platform.bukkit.admin.AeroMenu;
import dev.aeroac.platform.bukkit.admin.BukkitAdminGui;
import dev.aeroac.platform.bukkit.admin.MenuItems;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The four dataset screens, all rendered from one cached summary.
 *
 * <p>None of them reads a file. The summary behind them is built on a background thread and reused
 * until it ages out, because a dataset only grows and an operator opening a menu must not pay for
 * that growth.
 *
 * <p>Two fields are absent on purpose and say so where they would have appeared: attack window
 * counts and ping buckets are computed by the offline audit from telemetry this interface never
 * opens. An estimate in their place would be quoted as a fact within a week.
 */
public final class DatasetMenu extends AeroMenu {
    public enum Kind { OVERVIEW, COVERAGE, RECENT, REVIEW }

    private static final int PER_PAGE = 45;

    private final Kind kind;
    private int page;

    public DatasetMenu(BukkitAdminGui gui, Player viewer, Kind kind, int page) {
        super(gui, viewer);
        this.kind = kind;
        this.page = page;
    }

    @Override protected String title() {
        return MenuItems.HEADER + "AERO › " + switch (kind) {
            case OVERVIEW -> "DATASET";
            case COVERAGE -> "COVERAGE";
            case RECENT -> "RECENT SESSIONS";
            case REVIEW -> "REVIEW QUEUE";
        };
    }

    @Override public String permission() { return kind == Kind.REVIEW ? AdminPermissions.TRAINING_REVIEW : AdminPermissions.TRAINING_OVERVIEW; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        DatasetSummary summary = service().datasetSummary();
        if (!summary.ready()) {
            inventory.setItem(22, MenuItems.item(Material.CLOCK,
                    MenuItems.MUTED + (summary.failedToLoad() ? AeroMessages.tr("gui.training.unavailable") : AeroMessages.tr("gui.training.loading")),
                    MenuItems.wrap(MenuItems.MUTED, summary.failedToLoad()
                            ? summary.error()
                            : AeroMessages.tr("gui.training.reading_session_metadata_on_a_background_thread")
                                    + AeroMessages.tr("gui.training.this_screen_fills_in_when_it_finishes"), 44)));
            footer(inventory, this::back, AeroMessages.tr("gui.training.the_training_centre"));
            return;
        }
        switch (kind) {
            case OVERVIEW -> drawOverview(inventory, summary);
            case COVERAGE -> drawCoverage(inventory, summary);
            case RECENT -> drawSessions(inventory, summary.recent());
            case REVIEW -> drawSessions(inventory, summary.attention());
        }
        footer(inventory, this::back, AeroMessages.tr("gui.training.the_training_centre"));
    }

    private void drawOverview(Inventory inventory, DatasetSummary summary) {
        inventory.setItem(4, MenuItems.item(Material.BOOKSHELF, MenuItems.HEADER + AeroMessages.tr("gui.training.totals"),
                MenuItems.line(AeroMessages.tr("gui.training.sessions"), String.valueOf(summary.sessions())),
                MenuItems.line(AeroMessages.tr("gui.training.players"), String.valueOf(summary.players())),
                MenuItems.line("LEGIT", String.valueOf(summary.legit()), MenuItems.GOOD),
                MenuItems.line("CHEAT", String.valueOf(summary.cheat()), MenuItems.BAD),
                MenuItems.line("UNLABELED", String.valueOf(summary.unlabeled()), MenuItems.WARN),
                "",
                MenuItems.line(AeroMessages.tr("gui.training.recorded_time"), AdminStyle.duration(summary.totalDurationSeconds())),
                MenuItems.line(AeroMessages.tr("gui.training.frames"), String.valueOf(summary.totalFrames())),
                MenuItems.line(AeroMessages.tr("gui.training.combat_time_audit"), summary.audit().present() ? AdminStyle.duration((long) summary.audit().combatSeconds()) : AdminStyle.NO_DATA),
                MenuItems.line(AeroMessages.tr("gui.training.attack_windows"), summary.audit().present() ? String.valueOf(summary.audit().attackWindows()) : AdminStyle.NO_DATA, MenuItems.MUTED),
                MenuItems.note(AeroMessages.tr("gui.training.counted_by_the_offline_audit_not_here")),
                "",
                MenuItems.line(AeroMessages.tr("gui.training.incomplete"), String.valueOf(summary.incomplete()),
                        summary.incomplete() > 0 ? MenuItems.WARN : MenuItems.VALUE),
                MenuItems.line(AeroMessages.tr("gui.training.with_dropped_records"), String.valueOf(summary.withDropped()),
                        summary.withDropped() > 0 ? MenuItems.WARN : MenuItems.VALUE),
                MenuItems.line(AeroMessages.tr("gui.training.human_reviewed"), String.valueOf(summary.reviewed()))));

        inventory.setItem(19, breakdown(Material.RED_CONCRETE, "CHEAT — FAMILY", summary.cheatFamilies()));
        inventory.setItem(20, breakdown(Material.RED_CONCRETE, "CHEAT — CLIENT", summary.clientFamilies()));
        inventory.setItem(21, breakdown(Material.RED_CONCRETE, "CHEAT — CONFIGURATION",
                summary.configurations()));
        inventory.setItem(22, breakdown(Material.RED_CONCRETE, "CHEAT — ASSIST STRENGTH",
                summary.assistStrengths()));
        inventory.setItem(23, breakdown(Material.RED_CONCRETE, "CHEAT — SCENARIO", summary.cheatScenarios()));
        inventory.setItem(29, breakdown(Material.LIME_CONCRETE, "LEGIT — SCENARIO",
                summary.legitScenarios()));
        inventory.setItem(30, breakdown(Material.LIME_CONCRETE, "LEGIT — PROTOCOL",
                summary.legitProtocols()));

        List<String> ping = new ArrayList<>();
        if (summary.audit().present()) summary.audit().legitPingBuckets().forEach((bucket, count) -> ping.add(MenuItems.line(bucket, String.valueOf(count))));
        else ping.add(MenuItems.line(AeroMessages.tr("gui.training.ping_buckets"), AdminStyle.NO_DATA, MenuItems.MUTED));
        ping.add("");
        ping.addAll(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.training.session_metadata_records_the_client_protocol_but_never_a_pin")
                        + AeroMessages.tr("gui.training.telemetry_frames_which_the_offline_audit_reads_and_this_scre"), 44));
        inventory.setItem(31, MenuItems.item(Material.GRAY_CONCRETE,
                MenuItems.HEADER + AeroMessages.tr("gui.training.legit_ping"), ping));

        inventory.setItem(33, breakdown(Material.PAPER, "IMPORTED AUDIT VERDICTS", summary.auditVerdicts()));
        if (summary.truncated()) {
            inventory.setItem(40, MenuItems.item(Material.HOPPER, MenuItems.WARN + AeroMessages.tr("gui.training.truncated"),
                    MenuItems.note(AeroMessages.tr("gui.training.only_the_newest_sessions_were_read_raise")),
                    MenuItems.note(AeroMessages.tr("gui.training.neural_gui_dataset_max_sessions_to_include_more"))));
        }
    }

    private ItemStack breakdown(Material material, String name, Map<String, Integer> counts) {
        List<String> lore = new ArrayList<>();
        if (counts.isEmpty()) {
            lore.add(MenuItems.note(AeroMessages.tr("gui.training.nothing_recorded_yet")));
        } else {
            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(12)
                    .forEach(entry -> lore.add(MenuItems.line(DatasetSummary.UNSET.equals(entry.getKey())
                            ? AeroMessages.tr("gui.dataset.unset") : entry.getKey(),
                            String.valueOf(entry.getValue()))));
            if (counts.size() > 12) lore.add(MenuItems.note(AeroMessages.tr("gui.training.and") + (counts.size() - 12) + AeroMessages.tr("gui.training.more")));
        }
        return MenuItems.item(material, MenuItems.HEADER + name, lore);
    }

    private void drawCoverage(Inventory inventory, DatasetSummary summary) {
        CoverageReport report = CoverageReport.of(summary, service().config().training());
        int slot = 9;
        String group = null;
        var selected = AdminSnapshot.page(report.rows(), page, 36);
        page = selected.index();
        inventory.setItem(45, MenuItems.previousPage(selected.hasPrevious()));
        inventory.setItem(53, MenuItems.nextPage(selected.hasNext()));
        for (CoverageReport.Row row : selected.items()) {
            if (!row.group().equals(group)) group = row.group();
            List<String> lore = new ArrayList<>();
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.group"), AdminLabels.coverageGroup(row.group())));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.recorded"), row.measured() ? String.valueOf(row.count()) : AdminStyle.NO_DATA));
            if (row.goal() > 0) {
                lore.add(MenuItems.line(AeroMessages.tr("gui.training.goal"), String.valueOf(row.goal())));
                lore.add((row.shortfall() ? MenuItems.WARN : MenuItems.GOOD)
                        + AdminStyle.bar(row.fraction(), 12));
            } else {
                lore.add(MenuItems.note(AeroMessages.tr("gui.training.no_goal_set_for_this_category")));
            }
            inventory.setItem(slot++, MenuItems.item(
                    !row.measured() ? Material.GRAY_STAINED_GLASS_PANE : row.shortfall() ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    (!row.measured() ? MenuItems.MUTED : row.shortfall() ? MenuItems.WARN : MenuItems.GOOD) + AdminLabels.coverageLabel(row.label())
                            + (row.shortfall() ? " ⚠" : ""), lore));
        }
        List<String> caveat = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.training.these_goals_are_yours_set_in_neural_gui_training_meeting_all")
                        + AeroMessages.tr("gui.training.mean_the_dataset_supports_any_particular_false_positive_rate")
                        + AeroMessages.tr("gui.training.rate_that_is_measured_by_evaluating_a_model_on_held_out_revi")
                        + AeroMessages.tr("gui.training.never_by_counting_recordings"), 44));
        inventory.setItem(49, MenuItems.item(Material.MAP, MenuItems.HEADER + AeroMessages.tr("gui.training.what_this_is_not"), caveat));
    }

    private void drawSessions(Inventory inventory, List<SessionRecord> sessions) {
        List<SessionRecord> ordered = new ArrayList<>(sessions);
        ordered.sort(Comparator.comparingLong(SessionRecord::startTimestamp).reversed());
        AdminSnapshot.Page<SessionRecord> current = AdminSnapshot.page(ordered, page, PER_PAGE);
        page = current.index();

        int slot = 0;
        for (SessionRecord record : current.items()) {
            inventory.setItem(slot++, sessionItem(record));
        }
        if (ordered.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.LIME_STAINED_GLASS_PANE,
                    MenuItems.GOOD + (kind == Kind.REVIEW ? AeroMessages.tr("gui.training.nothing_to_review") : AeroMessages.tr("gui.training.no_sessions")),
                    MenuItems.note(kind == Kind.REVIEW
                            ? AeroMessages.tr("gui.training.no_session_is_flagged_and_no_review_verdict_was_imported")
                            : AeroMessages.tr("gui.training.nothing_has_been_recorded_on_this_server_yet"))));
        }
        inventory.setItem(45, MenuItems.previousPage(current.hasPrevious()));
        inventory.setItem(53, MenuItems.nextPage(current.hasNext()));
        if (kind == Kind.REVIEW) {
            inventory.setItem(49, MenuItems.item(Material.BOOK, MenuItems.HEADER + AeroMessages.tr("gui.training.about_review"),
                    MenuItems.wrap(MenuItems.MUTED,
                            AeroMessages.tr("gui.training.a_human_review_is_a_person_a_timestamp_and_a_note_recorded_i")
                                    + AeroMessages.tr("gui.training.manifest_by_the_python_tooling_nothing_in_this_plugin_can_ma")
                                    + AeroMessages.tr("gui.training.session_reviewed_and_a_session_listed_here_is_flagged_for_a")
                                    + AeroMessages.tr("gui.training.not_judged"), 44)));
        }
    }

    private ItemStack sessionItem(SessionRecord record) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.label"), record.label() == null ? "?" : record.label(),
                record.cheat() ? MenuItems.BAD : record.legit() ? MenuItems.GOOD : MenuItems.WARN));
        if (record.cheat()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.family"), AdminStyle.blankIfEmpty(record.cheatFamily(), AeroMessages.tr("gui.training.unset"))));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.assist"), AdminStyle.blankIfEmpty(record.assistStrength(), AeroMessages.tr("gui.training.unset"))));
        }
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.client"), AdminStyle.blankIfEmpty(record.clientFamily(), AeroMessages.tr("gui.training.unset"))));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.scenario"), AdminStyle.blankIfEmpty(record.scenario(), AeroMessages.tr("gui.training.unset"))));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.duration"), AdminStyle.duration(record.durationSeconds())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.frames"), String.valueOf(record.frames())));
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.closed"), record.complete() ? AeroMessages.tr("gui.training.cleanly") : AeroMessages.tr("gui.training.incomplete_2"),
                record.complete() ? MenuItems.GOOD : MenuItems.WARN));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.close_reason"), AdminStyle.blankIfEmpty(record.closeReason(), "-")));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.dropped_records"), String.valueOf(record.droppedRecords()),
                record.droppedRecords() > 0 ? MenuItems.BAD : MenuItems.VALUE));
        if (record.failure() != null) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.failure"), record.failure(), MenuItems.BAD));
        }
        lore.add("");
        if (record.audited()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.audit_verdict"), record.auditVerdict(),
                    "GOOD".equals(record.auditVerdict()) ? MenuItems.GOOD : MenuItems.WARN));
            if (record.auditReason() != null && !record.auditReason().isBlank()) {
                lore.addAll(MenuItems.wrap(MenuItems.MUTED, record.auditReason(), 44));
            }
        } else {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.audit_verdict"), AeroMessages.tr("gui.training.no_matching_imported_audit"), MenuItems.MUTED));
        }
        if (record.humanReviewed()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.reviewed_by"), record.reviewer()));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.reviewed_at"), AdminStyle.blankIfEmpty(record.reviewedAt(), "-")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.reviewed_label"),
                    AdminStyle.blankIfEmpty(record.reviewedLabel(), "-")));
        } else {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.human_review"), AeroMessages.tr("gui.training.none"), MenuItems.MUTED));
        }
        if (kind == Kind.REVIEW && record.needsAttention()) {
            lore.add("");
            lore.add(MenuItems.WARN + AeroMessages.tr("gui.training.flagged") + MenuItems.MUTED + record.attentionReason());
        }
        Material material = record.needsAttention() ? Material.ORANGE_STAINED_GLASS_PANE
                : record.cheat() ? Material.RED_STAINED_GLASS_PANE
                : record.legit() ? Material.LIME_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE;
        return MenuItems.item(material, MenuItems.LABEL + record.shortId() + " "
                + MenuItems.MUTED + AdminStyle.blankIfEmpty(record.label(), "?"), lore);
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
        } else if (slot == 53) {
            page++;
            redraw();
        }
    }

    private void back() {
        gui.show(new TrainingMenu(gui, viewer));
    }
}
