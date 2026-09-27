package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.locale.AeroMessages;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminStyle;
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
import java.util.Map;

/**
 * One evidence snapshot as a replay: what the model said, what else had accumulated, and the
 * player's aim sample by sample around the moment risk crossed the threshold. Each pane is one
 * sample (50 ms): red is an attack, green the crosshair inside the target's box, yellow a tracked
 * target the crosshair is off, grey no target. Panes before the event are glass, after it concrete.
 */
public final class SnapshotDetailMenu extends AeroMenu {
    private static final int FIRST_PANE = 9;
    private static final int PANES = 36;
    /** Of the 36 panes, how many show the run-up; the rest show what followed. */
    private static final int BEFORE = 24;

    private final Path directory;
    private final SnapshotIndex.Summary summary;
    private final SnapshotsMenu parent;
    private volatile SnapshotIndex.Detail detail;
    private volatile boolean failed;
    private volatile SnapshotReviews.Review review;
    private static final int SLOT_CHEAT = 0;
    private static final int SLOT_LEGIT = 8;

    public SnapshotDetailMenu(BukkitAdminGui gui, Player viewer, Path directory, SnapshotIndex.Summary summary,
                              SnapshotsMenu parent) {
        super(gui, viewer);
        this.directory = directory;
        this.summary = summary;
        this.parent = parent;
        Bukkit.getScheduler().runTaskAsynchronously(AeroACBukkitLoaderPlugin.LOADER, () -> {
            SnapshotIndex.Detail result = SnapshotIndex.detail(directory, summary.file());
            SnapshotReviews.Review verdict = SnapshotReviews.read(SnapshotReviews.directoryFor(directory), summary.eventId());
            onMain(() -> {
                detail = result;
                review = verdict;
                failed = result == null;
                redraw();
            });
        });
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.snapshots.detail_title"); }

    @Override public String permission() { return AdminPermissions.PROFILE; }

    @Override protected int rows() { return 6; }

    @Override public boolean live() { return false; }

    @Override protected void draw(Inventory inventory) {
        SnapshotIndex.Detail loaded = detail;
        inventory.setItem(4, summaryItem(loaded));
        if (loaded == null) {
            inventory.setItem(22, MenuItems.item(Material.PAPER, MenuItems.MUTED
                    + AeroMessages.tr(failed ? "gui.snapshots.unreadable" : "gui.loading")));
        } else {
            inventory.setItem(2, headsItem(loaded));
            inventory.setItem(6, evidenceItem(loaded));
            drawReplay(inventory, loaded.samples());
            drawVerdict(inventory);
        }
        footer(inventory, () -> gui.show(parent.reopen()), AeroMessages.tr("gui.snapshots.back_list"));
    }

    /** CHEAT and LEGIT buttons; the current verdict is highlighted and clicking it again withdraws it. */
    private void drawVerdict(Inventory inventory) {
        SnapshotReviews.Review current = review;
        boolean cheat = current != null && current.verdict() == SnapshotReviews.Verdict.CHEAT;
        boolean legit = current != null && current.verdict() == SnapshotReviews.Verdict.LEGIT;
        inventory.setItem(SLOT_CHEAT, MenuItems.item(cheat ? Material.RED_CONCRETE : Material.RED_WOOL,
                MenuItems.BAD + (cheat ? "✔ " : "") + AeroMessages.tr("gui.review.cheat"), verdictLore(current, cheat)));
        inventory.setItem(SLOT_LEGIT, MenuItems.item(legit ? Material.LIME_CONCRETE : Material.LIME_WOOL,
                MenuItems.GOOD + (legit ? "✔ " : "") + AeroMessages.tr("gui.review.legit"), verdictLore(current, legit)));
        lockSlot(inventory, SLOT_CHEAT, AdminPermissions.TRAINING_REVIEW);
        lockSlot(inventory, SLOT_LEGIT, AdminPermissions.TRAINING_REVIEW);
    }

    private static List<String> verdictLore(SnapshotReviews.Review current, boolean selected) {
        List<String> lore = new ArrayList<>();
        if (current != null) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.review.current"), current.verdict().name()));
            lore.add(MenuItems.line(AeroMessages.tr("gui.mark.by"), current.reviewer() + ", "
                    + AdminStyle.age(System.currentTimeMillis() - current.reviewedAtMillis())));
            lore.add("");
        }
        lore.add(MenuItems.note(AeroMessages.tr(selected ? "gui.review.click_clear" : "gui.review.click_set")));
        lore.add(MenuItems.note(AeroMessages.tr("gui.review.weak")));
        return lore;
    }

    private void setVerdict(SnapshotReviews.Verdict verdict) {
        if (!permitted(AdminPermissions.TRAINING_REVIEW)) {
            deny(AdminPermissions.TRAINING_REVIEW);
            return;
        }
        SnapshotReviews.Review current = review;
        boolean clear = current != null && current.verdict() == verdict;
        String reviewer = viewer.getName();
        Path reviews = SnapshotReviews.directoryFor(directory);
        Bukkit.getScheduler().runTaskAsynchronously(AeroACBukkitLoaderPlugin.LOADER, () -> {
            String message;
            try {
                if (clear) SnapshotReviews.clear(reviews, summary.eventId());
                else SnapshotReviews.write(reviews, summary.eventId(), summary.file(), verdict, reviewer, System.currentTimeMillis());
                message = MenuItems.GOOD + AeroMessages.tr(clear ? "gui.review.cleared" : "gui.review.saved", verdict.name());
            } catch (java.io.IOException | RuntimeException error) {
                message = MenuItems.BAD + AeroMessages.tr("gui.review.failed") + error.getMessage();
            }
            SnapshotReviews.Review after = SnapshotReviews.read(reviews, summary.eventId());
            String text = message;
            onMain(() -> {
                review = after;
                viewer.sendMessage(text);
                redraw();
            });
        });
    }

    private org.bukkit.inventory.ItemStack summaryItem(SnapshotIndex.Detail loaded) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.when"), AdminStyle.age(System.currentTimeMillis() - summary.timestampMillis())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.state"), summary.stateBefore() + " → " + summary.stateAfter()));
        lore.add(MenuItems.line(AeroMessages.tr("gui.risk"), AdminStyle.number(summary.riskBefore(), 2) + " → " + AdminStyle.number(summary.riskAfter(), 2)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.trigger"), summary.triggerType() + " " + AdminStyle.number(summary.triggerStrength(), 2)));
        if (summary.triggerSource() != null) lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.source"), summary.triggerSource()));
        if (loaded != null) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.ping"), Double.isNaN(loaded.pingMs()) ? AdminStyle.NO_DATA
                    : AdminStyle.number(loaded.pingMs(), 0) + "ms"));
            lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.protocol"), String.valueOf(loaded.protocol())));
        }
        lore.add("");
        lore.add(MenuItems.note(AeroMessages.tr("gui.snapshots.legend_1")));
        lore.add(MenuItems.note(AeroMessages.tr("gui.snapshots.legend_2")));
        lore.add(MenuItems.note(AeroMessages.tr("gui.snapshots.unlabeled")));
        return MenuItems.item(Material.FILLED_MAP, MenuItems.HEADER + summary.triggerType() + " · " + summary.stateAfter(), lore);
    }

    private static org.bukkit.inventory.ItemStack headsItem(SnapshotIndex.Detail loaded) {
        List<String> lore = new ArrayList<>();
        if (loaded.heads().isEmpty()) lore.add(MenuItems.note(AeroMessages.tr("gui.snapshots.no_prediction")));
        for (Map.Entry<String, Double> head : loaded.heads().entrySet()) lore.add(MenuItems.headLine(head.getKey(), head.getValue()));
        if (loaded.summary().model() != null) lore.add(MenuItems.line(AeroMessages.tr("gui.model"), loaded.summary().model()));
        return MenuItems.item(Material.ENDER_EYE, MenuItems.HEADER + AeroMessages.tr("gui.snapshots.model_said"), lore);
    }

    private static org.bukkit.inventory.ItemStack evidenceItem(SnapshotIndex.Detail loaded) {
        List<String> lore = new ArrayList<>();
        if (loaded.evidenceCounts().isEmpty()) lore.add(MenuItems.note(AdminStyle.NO_DATA));
        loaded.evidenceCounts().forEach((type, count) -> lore.add(MenuItems.line(type, String.valueOf(count))));
        return MenuItems.item(Material.BOOK, MenuItems.HEADER + AeroMessages.tr("gui.snapshots.evidence_until"), lore);
    }

    /** 24 samples of run-up and 12 of aftermath around the event, one pane each. */
    private static void drawReplay(Inventory inventory, List<SnapshotIndex.Sample> samples) {
        int event = 0;
        while (event < samples.size() && !samples.get(event).afterEvent()) event++;
        int start = Math.max(0, Math.min(event - BEFORE, samples.size() - PANES));
        for (int pane = 0; pane < PANES; pane++) {
            int index = start + pane;
            int slot = FIRST_PANE + pane;
            if (index >= samples.size()) continue;
            SnapshotIndex.Sample sample = samples.get(index);
            List<String> lore = new ArrayList<>();
            lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.time"), (sample.afterEvent() ? "+" : "") + sample.offsetMs() + "ms"));
            lore.add(MenuItems.line(AeroMessages.tr("gui.delta_yaw_pitch"), AdminStyle.number(sample.deltaYaw(), 2)
                    + " / " + AdminStyle.number(sample.deltaPitch(), 2)));
            lore.add(MenuItems.line(AeroMessages.tr("gui.aim_error"), AdminStyle.number(sample.aimError(), 2)));
            lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.distance"), AdminStyle.number(sample.distance(), 2)));
            lore.add(MenuItems.line(AeroMessages.tr("gui.snapshots.crosshair"), !sample.targetPresent()
                    ? AdminStyle.NO_DATA : AeroMessages.tr(sample.onTarget() ? "gui.snapshots.on" : "gui.snapshots.off")));
            String title = (sample.attack() ? MenuItems.BAD + "⚔ " : sample.onTarget() ? MenuItems.GOOD : MenuItems.MUTED)
                    + AeroMessages.tr(sample.afterEvent() ? "gui.snapshots.after" : "gui.snapshots.before") + " " + (index + 1);
            inventory.setItem(slot, MenuItems.item(material(sample), title, lore));
        }
    }

    private static Material material(SnapshotIndex.Sample sample) {
        boolean after = sample.afterEvent();
        if (sample.attack()) return after ? Material.RED_CONCRETE : Material.RED_STAINED_GLASS_PANE;
        if (!sample.targetPresent()) return after ? Material.GRAY_CONCRETE : Material.GRAY_STAINED_GLASS_PANE;
        if (sample.onTarget()) return after ? Material.LIME_CONCRETE : Material.LIME_STAINED_GLASS_PANE;
        return after ? Material.YELLOW_CONCRETE : Material.YELLOW_STAINED_GLASS_PANE;
    }

    @Override public void click(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (isFooterClose(slot, rows())) viewer.closeInventory();
        else if (isFooterBack(slot, rows())) gui.show(parent.reopen());
        else if (slot == SLOT_CHEAT && detail != null) setVerdict(SnapshotReviews.Verdict.CHEAT);
        else if (slot == SLOT_LEGIT && detail != null) setVerdict(SnapshotReviews.Verdict.LEGIT);
    }
}
