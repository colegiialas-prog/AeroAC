package ac.grim.grimac.platform.bukkit.admin.training;

import ac.grim.grimac.neural.admin.AdminPermissions;

import ac.grim.grimac.neural.admin.AdminSnapshot;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.admin.training.CoverageReport;
import ac.grim.grimac.neural.admin.training.DatasetSummary;
import ac.grim.grimac.neural.admin.training.SessionRecord;
import ac.grim.grimac.platform.bukkit.admin.AeroMenu;
import ac.grim.grimac.platform.bukkit.admin.BukkitAdminGui;
import ac.grim.grimac.platform.bukkit.admin.MenuItems;
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
                    MenuItems.MUTED + (summary.failedToLoad() ? "UNAVAILABLE" : "LOADING"),
                    MenuItems.wrap(MenuItems.MUTED, summary.failedToLoad()
                            ? summary.error()
                            : "Reading session metadata on a background thread. "
                                    + "This screen fills in when it finishes.", 44)));
            footer(inventory, this::back, "the training centre");
            return;
        }
        switch (kind) {
            case OVERVIEW -> drawOverview(inventory, summary);
            case COVERAGE -> drawCoverage(inventory, summary);
            case RECENT -> drawSessions(inventory, summary.recent());
            case REVIEW -> drawSessions(inventory, summary.attention());
        }
        footer(inventory, this::back, "the training centre");
    }

    private void drawOverview(Inventory inventory, DatasetSummary summary) {
        inventory.setItem(4, MenuItems.item(Material.BOOKSHELF, MenuItems.HEADER + "TOTALS",
                MenuItems.line("Sessions", String.valueOf(summary.sessions())),
                MenuItems.line("Players", String.valueOf(summary.players())),
                MenuItems.line("LEGIT", String.valueOf(summary.legit()), MenuItems.GOOD),
                MenuItems.line("CHEAT", String.valueOf(summary.cheat()), MenuItems.BAD),
                MenuItems.line("UNLABELED", String.valueOf(summary.unlabeled()), MenuItems.WARN),
                "",
                MenuItems.line("Recorded time", AdminStyle.duration(summary.totalDurationSeconds())),
                MenuItems.line("Frames", String.valueOf(summary.totalFrames())),
                MenuItems.line("Combat time (audit)", summary.audit().present() ? AdminStyle.duration((long) summary.audit().combatSeconds()) : AdminStyle.NO_DATA),
                MenuItems.line("Attack windows", summary.audit().present() ? String.valueOf(summary.audit().attackWindows()) : AdminStyle.NO_DATA, MenuItems.MUTED),
                MenuItems.note("  counted by the offline audit, not here"),
                "",
                MenuItems.line("Incomplete", String.valueOf(summary.incomplete()),
                        summary.incomplete() > 0 ? MenuItems.WARN : MenuItems.VALUE),
                MenuItems.line("With dropped records", String.valueOf(summary.withDropped()),
                        summary.withDropped() > 0 ? MenuItems.WARN : MenuItems.VALUE),
                MenuItems.line("Human reviewed", String.valueOf(summary.reviewed()))));

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
        else ping.add(MenuItems.line("Ping buckets", AdminStyle.NO_DATA, MenuItems.MUTED));
        ping.add("");
        ping.addAll(MenuItems.wrap(MenuItems.MUTED,
                "Session metadata records the client protocol but never a ping. Ping lives in the "
                        + "telemetry frames, which the offline audit reads and this screen does not.", 44));
        inventory.setItem(31, MenuItems.item(Material.GRAY_CONCRETE,
                MenuItems.HEADER + "LEGIT — PING", ping));

        inventory.setItem(33, breakdown(Material.PAPER, "IMPORTED AUDIT VERDICTS", summary.auditVerdicts()));
        if (summary.truncated()) {
            inventory.setItem(40, MenuItems.item(Material.HOPPER, MenuItems.WARN + "TRUNCATED",
                    MenuItems.note("Only the newest sessions were read; raise"),
                    MenuItems.note("neural.gui.dataset.max-sessions to include more.")));
        }
    }

    private ItemStack breakdown(Material material, String name, Map<String, Integer> counts) {
        List<String> lore = new ArrayList<>();
        if (counts.isEmpty()) {
            lore.add(MenuItems.note("Nothing recorded yet."));
        } else {
            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(12)
                    .forEach(entry -> lore.add(MenuItems.line(entry.getKey(),
                            String.valueOf(entry.getValue()))));
            if (counts.size() > 12) lore.add(MenuItems.note("... and " + (counts.size() - 12) + " more"));
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
            lore.add(MenuItems.line("Group", row.group()));
            lore.add(MenuItems.line("Recorded", row.measured() ? String.valueOf(row.count()) : AdminStyle.NO_DATA));
            if (row.goal() > 0) {
                lore.add(MenuItems.line("Goal", String.valueOf(row.goal())));
                lore.add((row.shortfall() ? MenuItems.WARN : MenuItems.GOOD)
                        + AdminStyle.bar(row.fraction(), 12));
            } else {
                lore.add(MenuItems.note("No goal set for this category."));
            }
            inventory.setItem(slot++, MenuItems.item(
                    !row.measured() ? Material.GRAY_STAINED_GLASS_PANE : row.shortfall() ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    (!row.measured() ? MenuItems.MUTED : row.shortfall() ? MenuItems.WARN : MenuItems.GOOD) + row.label()
                            + (row.shortfall() ? " ⚠" : ""), lore));
        }
        List<String> caveat = new ArrayList<>(MenuItems.wrap(MenuItems.MUTED,
                "These goals are yours, set in neural.gui.training.*. Meeting all of them does not "
                        + "mean the dataset supports any particular false-positive rate or detection "
                        + "rate: that is measured by evaluating a model on held-out reviewed sessions, "
                        + "never by counting recordings.", 44));
        inventory.setItem(49, MenuItems.item(Material.MAP, MenuItems.HEADER + "WHAT THIS IS NOT", caveat));
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
                    MenuItems.GOOD + (kind == Kind.REVIEW ? "NOTHING TO REVIEW" : "NO SESSIONS"),
                    MenuItems.note(kind == Kind.REVIEW
                            ? "No session is flagged and no REVIEW verdict was imported."
                            : "Nothing has been recorded on this server yet.")));
        }
        inventory.setItem(45, MenuItems.previousPage(current.hasPrevious()));
        inventory.setItem(53, MenuItems.nextPage(current.hasNext()));
        if (kind == Kind.REVIEW) {
            inventory.setItem(49, MenuItems.item(Material.BOOK, MenuItems.HEADER + "ABOUT REVIEW",
                    MenuItems.wrap(MenuItems.MUTED,
                            "A human review is a person, a timestamp and a note recorded in the golden "
                                    + "manifest by the Python tooling. Nothing in this plugin can mark a "
                                    + "session reviewed, and a session listed here is flagged for a look, "
                                    + "not judged.", 44)));
        }
    }

    private ItemStack sessionItem(SessionRecord record) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line("Label", record.label() == null ? "?" : record.label(),
                record.cheat() ? MenuItems.BAD : record.legit() ? MenuItems.GOOD : MenuItems.WARN));
        if (record.cheat()) {
            lore.add(MenuItems.line("Family", AdminStyle.blankIfEmpty(record.cheatFamily(), "(unset)")));
            lore.add(MenuItems.line("Assist", AdminStyle.blankIfEmpty(record.assistStrength(), "(unset)")));
        }
        lore.add(MenuItems.line("Client", AdminStyle.blankIfEmpty(record.clientFamily(), "(unset)")));
        lore.add(MenuItems.line("Scenario", AdminStyle.blankIfEmpty(record.scenario(), "(unset)")));
        lore.add(MenuItems.line("Duration", AdminStyle.duration(record.durationSeconds())));
        lore.add(MenuItems.line("Frames", String.valueOf(record.frames())));
        lore.add("");
        lore.add(MenuItems.line("Closed", record.complete() ? "cleanly" : "incomplete",
                record.complete() ? MenuItems.GOOD : MenuItems.WARN));
        lore.add(MenuItems.line("Close reason", AdminStyle.blankIfEmpty(record.closeReason(), "-")));
        lore.add(MenuItems.line("Dropped records", String.valueOf(record.droppedRecords()),
                record.droppedRecords() > 0 ? MenuItems.BAD : MenuItems.VALUE));
        if (record.failure() != null) {
            lore.add(MenuItems.line("Failure", record.failure(), MenuItems.BAD));
        }
        lore.add("");
        if (record.audited()) {
            lore.add(MenuItems.line("Audit verdict", record.auditVerdict(),
                    "GOOD".equals(record.auditVerdict()) ? MenuItems.GOOD : MenuItems.WARN));
            if (record.auditReason() != null && !record.auditReason().isBlank()) {
                lore.addAll(MenuItems.wrap(MenuItems.MUTED, record.auditReason(), 44));
            }
        } else {
            lore.add(MenuItems.line("Audit verdict", "no matching imported audit", MenuItems.MUTED));
        }
        if (record.humanReviewed()) {
            lore.add(MenuItems.line("Reviewed by", record.reviewer()));
            lore.add(MenuItems.line("Reviewed at", AdminStyle.blankIfEmpty(record.reviewedAt(), "-")));
            lore.add(MenuItems.line("Reviewed label",
                    AdminStyle.blankIfEmpty(record.reviewedLabel(), "-")));
        } else {
            lore.add(MenuItems.line("Human review", "none", MenuItems.MUTED));
        }
        if (kind == Kind.REVIEW && record.needsAttention()) {
            lore.add("");
            lore.add(MenuItems.WARN + "Flagged: " + MenuItems.MUTED + record.attentionReason());
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
