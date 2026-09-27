package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.locale.AeroMessages;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.dataset.DatasetManager;
import dev.aeroac.neural.dataset.SnapshotIndex;
import dev.aeroac.neural.dataset.SnapshotReviews;
import dev.aeroac.platform.bukkit.AeroACBukkitLoaderPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The evidence snapshots recorded for one player, newest first: each is a moment where risk
 * crossed the snapshot threshold, with the frames around it. Loaded from disk off the main thread;
 * nothing redraws on a timer, a snapshot never changes once written.
 */
public final class SnapshotsMenu extends AeroMenu {
    private static final int PER_PAGE = 45;

    private final UUID target;
    private final String name;
    private final boolean fromSuspicious;
    private volatile List<SnapshotIndex.Summary> loaded;
    private volatile java.util.Map<String, SnapshotReviews.Review> reviews = java.util.Map.of();
    private static final int SLOT_ALL_CHEAT = 47;
    private static final int SLOT_ALL_LEGIT = 51;
    private volatile String problem;
    private Path directory;

    public SnapshotsMenu(BukkitAdminGui gui, Player viewer, UUID target, String name, boolean fromSuspicious) {
        super(gui, viewer);
        this.target = target;
        this.name = name;
        this.fromSuspicious = fromSuspicious;
        load();
    }

    private void load() {
        DatasetManager datasets = gui.neural().datasets();
        if (datasets == null) {
            problem = AeroMessages.tr("gui.snapshots.no_storage");
            return;
        }
        directory = datasets.snapshotsDirectory();
        String pseudonym = datasets.pseudonym(target);
        Path from = directory;
        Bukkit.getScheduler().runTaskAsynchronously(AeroACBukkitLoaderPlugin.LOADER, () -> {
            List<SnapshotIndex.Summary> result = SnapshotIndex.list(from, pseudonym, PER_PAGE);
            java.util.Map<String, SnapshotReviews.Review> verdicts = new java.util.HashMap<>();
            for (SnapshotIndex.Summary summary : result) {
                SnapshotReviews.Review review = SnapshotReviews.read(SnapshotReviews.directoryFor(from), summary.eventId());
                if (review != null) verdicts.put(summary.eventId(), review);
            }
            onMain(() -> {
                reviews = verdicts;
                loaded = result;
                redraw();
            });
        });
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.snapshots.title") + name; }

    @Override public String permission() { return AdminPermissions.PROFILE; }

    @Override protected int rows() { return 6; }

    @Override public boolean live() { return false; }

    @Override protected void draw(Inventory inventory) {
        List<SnapshotIndex.Summary> rows = loaded;
        if (problem != null || rows == null || rows.isEmpty()) {
            String text = problem != null ? problem : rows == null ? AeroMessages.tr("gui.loading") : AeroMessages.tr("gui.snapshots.none");
            inventory.setItem(22, MenuItems.item(Material.PAPER, MenuItems.MUTED + text,
                    MenuItems.note(AeroMessages.tr("gui.snapshots.what"))));
        } else {
            for (int i = 0; i < rows.size() && i < PER_PAGE; i++) inventory.setItem(i, row(rows.get(i)));
            inventory.setItem(SLOT_ALL_CHEAT, MenuItems.item(Material.RED_WOOL, MenuItems.BAD + AeroMessages.tr("gui.review.all_cheat"),
                    MenuItems.note(AeroMessages.tr("gui.review.all_note")), MenuItems.note(AeroMessages.tr("gui.action.confirm_first"))));
            inventory.setItem(SLOT_ALL_LEGIT, MenuItems.item(Material.LIME_WOOL, MenuItems.GOOD + AeroMessages.tr("gui.review.all_legit"),
                    MenuItems.note(AeroMessages.tr("gui.review.all_note")), MenuItems.note(AeroMessages.tr("gui.action.confirm_first"))));
            lockSlot(inventory, SLOT_ALL_CHEAT, AdminPermissions.TRAINING_REVIEW);
            lockSlot(inventory, SLOT_ALL_LEGIT, AdminPermissions.TRAINING_REVIEW);
        }
        footer(inventory, this::back, AeroMessages.tr("gui.snapshots.back"));
    }

    private org.bukkit.inventory.ItemStack row(SnapshotIndex.Summary summary) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.when"), AdminStyle.age(System.currentTimeMillis() - summary.timestampMillis())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.state"), summary.stateBefore() + " → " + summary.stateAfter()));
        lore.add(MenuItems.line(AeroMessages.tr("gui.risk"), AdminStyle.number(summary.riskBefore(), 2) + " → " + AdminStyle.number(summary.riskAfter(), 2)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.trigger"), summary.triggerType() + " " + AdminStyle.number(summary.triggerStrength(), 2)));
        lore.add(MenuItems.riskLine(summary.overall()));
        if (summary.model() != null) lore.add(MenuItems.line(AeroMessages.tr("gui.model"), summary.model()));
        lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.frames"), String.valueOf(summary.frames())));
        SnapshotReviews.Review review = reviews.get(summary.eventId());
        lore.add(MenuItems.line(AeroMessages.tr("gui.review.current"), review == null ? MenuItems.MUTED + AeroMessages.tr("gui.review.none")
                : (review.verdict() == SnapshotReviews.Verdict.CHEAT ? MenuItems.BAD : MenuItems.GOOD) + review.verdict().name()));
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.snapshots.open")));
        String colour = "CONFIRMED".equals(summary.stateAfter()) ? MenuItems.BAD
                : "SUSPICIOUS".equals(summary.stateAfter()) ? MenuItems.WARN : MenuItems.HEADER;
        return MenuItems.item(Material.FILLED_MAP, colour + summary.triggerType() + " · " + summary.stateAfter(), lore);
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
        List<SnapshotIndex.Summary> rows = loaded;
        if ((slot == SLOT_ALL_CHEAT || slot == SLOT_ALL_LEGIT) && rows != null && !rows.isEmpty()) {
            labelAll(rows, slot == SLOT_ALL_CHEAT ? SnapshotReviews.Verdict.CHEAT : SnapshotReviews.Verdict.LEGIT);
            return;
        }
        if (rows == null || slot < 0 || slot >= rows.size() || slot >= PER_PAGE) return;
        gui.show(new SnapshotDetailMenu(gui, viewer, directory, rows.get(slot), this));
    }

    /** Labels every listed snapshot of this player at once, after a confirmation. */
    private void labelAll(List<SnapshotIndex.Summary> rows, SnapshotReviews.Verdict verdict) {
        if (!permitted(AdminPermissions.TRAINING_REVIEW)) {
            deny(AdminPermissions.TRAINING_REVIEW);
            return;
        }
        Path reviewsDirectory = SnapshotReviews.directoryFor(directory);
        String reviewer = viewer.getName();
        confirm("gui.review.all_title", "gui.review.all_question", new Object[]{rows.size(), name, verdict.name()},
                verdict == SnapshotReviews.Verdict.CHEAT ? "gui.review.all_cheat" : "gui.review.all_legit",
                List.of(MenuItems.note(AeroMessages.tr("gui.review.weak"))),
                () -> Bukkit.getScheduler().runTaskAsynchronously(AeroACBukkitLoaderPlugin.LOADER, () -> {
                    int written = 0;
                    for (SnapshotIndex.Summary summary : rows) {
                        try {
                            SnapshotReviews.write(reviewsDirectory, summary.eventId(), summary.file(), verdict, reviewer,
                                    System.currentTimeMillis());
                            written++;
                        } catch (java.io.IOException | RuntimeException skipped) {
                            // One unwritable file does not stop the rest; the count below says so.
                        }
                    }
                    int total = written;
                    onMain(() -> viewer.sendMessage(MenuItems.GOOD + AeroMessages.tr("gui.review.all_done", total, verdict.name())));
                }),
                this::reopen);
    }

    void back() { gui.show(new ProfileMenu(gui, viewer, target, fromSuspicious)); }

    /** A fresh copy for the detail screen's back arrow, so the list is re-read rather than stale. */
    SnapshotsMenu reopen() { return new SnapshotsMenu(gui, viewer, target, name, fromSuspicious); }
}
