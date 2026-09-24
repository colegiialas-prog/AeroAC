package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.neural.admin.AdminPermissions;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.admin.AdminDetailView;
import ac.grim.grimac.neural.admin.AdminPlayerView;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.risk.EvidenceType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The four history screens behind a profile, which differ only in what they list.
 *
 * <p>All of them read the same bounded ring buffers the engine already keeps. Nothing is stored for
 * the interface, so what fell out of a ring is gone, and each screen says how much it is holding
 * rather than implying it holds everything.
 */
public final class HistoryMenu extends AeroMenu {
    public enum Kind {
        PREDICTIONS("PREDICTIONS"),
        EVIDENCE("EVIDENCE"),
        GRIM_FLAGS("GRIM FLAGS"),
        MITIGATION("MITIGATION HISTORY");

        private final String title;

        Kind(String title) { this.title = title; }
    }

    private final UUID target;
    private final Kind kind;
    private final boolean fromSuspicious;
    private volatile AdminDetailView detail;
    private final java.util.concurrent.atomic.AtomicBoolean loading = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long loadedAt;

    public HistoryMenu(BukkitAdminGui gui, Player viewer, UUID target, Kind kind, boolean fromSuspicious) {
        super(gui, viewer);
        this.target = target;
        this.kind = kind;
        this.fromSuspicious = fromSuspicious;
    }

    @Override protected String title() {
        AdminPlayerView view = service().snapshot().find(target);
        return MenuItems.HEADER + "AERO › " + kind.title + " › " + (view == null ? "?" : view.name());
    }

    @Override public String permission() { return kind == Kind.MITIGATION ? AdminPermissions.MITIGATION : AdminPermissions.PROFILE; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        if (service().snapshot().find(target) == null) {
            detail = null;
            inventory.setItem(22, MenuItems.item(Material.BARRIER, MenuItems.MUTED + "PLAYER OFFLINE"));
            footer(inventory, this::back, "the profile");
            return;
        }
        requestDetail();
        AdminDetailView loaded = System.currentTimeMillis() - loadedAt <= service().config().refreshMs() * 3L ? detail : null;
        if (loaded == null) {
            inventory.setItem(22, MenuItems.item(Material.CLOCK, MenuItems.MUTED + "LOADING",
                    MenuItems.note("Reading the history from the player's connection.")));
            footer(inventory, this::back, "the profile");
            return;
        }
        List<ItemStack> items = switch (kind) {
            case PREDICTIONS -> predictions(loaded);
            case EVIDENCE -> evidence(loaded, false);
            case GRIM_FLAGS -> evidence(loaded, true);
            case MITIGATION -> mitigations(loaded);
        };
        if (items.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    MenuItems.MUTED + "NOTHING RECORDED", emptyReason()));
        }
        int slot = 0;
        for (ItemStack item : items) {
            if (slot >= 45) break;
            inventory.setItem(slot++, item);
        }
        footer(inventory, this::back, "the profile");
    }

    private List<String> emptyReason() {
        return switch (kind) {
            case PREDICTIONS -> MenuItems.wrap(MenuItems.MUTED,
                    "No prediction has come back for this player. Inference may be disabled or offline, "
                            + "or no complete window has been produced yet.", 44);
            case EVIDENCE -> MenuItems.wrap(MenuItems.MUTED,
                    "Nothing has moved this player's risk value.", 44);
            case GRIM_FLAGS -> MenuItems.wrap(MenuItems.MUTED,
                    "No deterministic check evidence has entered the risk engine for this player. "
                            + "Checks still run and still flag independently of this screen.", 44);
            case MITIGATION -> MenuItems.wrap(MenuItems.MUTED,
                    "No mitigation has ever been applied to this player.", 44);
        };
    }

    private List<ItemStack> predictions(AdminDetailView loaded) {
        List<ItemStack> items = new ArrayList<>();
        List<AdminDetailView.Prediction> rows = loaded.predictions();
        for (int i = rows.size() - 1; i >= 0; i--) {
            AdminDetailView.Prediction row = rows.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(MenuItems.line("Model", row.model() + " " + row.modelVersion()));
            lore.add(MenuItems.line("Calibrated", row.calibrated() ? "yes" : "no",
                    row.calibrated() ? MenuItems.GOOD : MenuItems.WARN));
            lore.add(MenuItems.line("Window", row.window() == null ? AdminStyle.NO_DATA : row.window()));
            lore.add("");
            lore.add(MenuItems.riskLine(row.overall()));
            for (int head = 0; head < row.headNames().length; head++) {
                if (row.headNames()[head].equals("overall")) continue;
                lore.add(MenuItems.headLine(row.headNames()[head], row.headValues()[head]));
            }
            lore.add("");
            lore.add(MenuItems.line("Latency", row.latencyMs() + "ms"));
            lore.add(MenuItems.line("Age", AdminStyle.age(row.ageMs())));
            if (!row.calibrated()) {
                lore.add("");
                lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                        "Uncalibrated: this number is a model score, not a probability.", 44));
            }
            items.add(MenuItems.item(Material.ENDER_EYE,
                    MenuItems.HEADER + "#" + (i + 1) + " " + AdminStyle.percent(row.overall()), lore));
        }
        return items;
    }

    private List<ItemStack> evidence(AdminDetailView loaded, boolean deterministicOnly) {
        List<ItemStack> items = new ArrayList<>();
        if (deterministicOnly) {
            for (EvidenceType type : EvidenceType.values()) {
                if (type.fromModel()) continue;
                long count = loaded.countOf(type);
                if (count == 0) continue;
                items.add(MenuItems.item(Material.COMPARATOR,
                        MenuItems.HEADER + type.name(),
                        MenuItems.line("Accepted as evidence", String.valueOf(count)),
                        "",
                        MenuItems.note("Counted since this connection began.")));
            }
        }
        List<AdminDetailView.Evidence> rows = loaded.evidence();
        for (int i = rows.size() - 1; i >= 0; i--) {
            AdminDetailView.Evidence row = rows.get(i);
            if (deterministicOnly && row.type().fromModel()) continue;
            List<String> lore = new ArrayList<>();
            lore.add(MenuItems.line("Strength", AdminStyle.number(row.strength(), 3),
                    row.strength() < 0 ? MenuItems.GOOD : MenuItems.WARN));
            lore.add(MenuItems.line("Source", AdminStyle.blankIfEmpty(row.source(), AdminStyle.NO_DATA)));
            lore.add(MenuItems.line("Age", AdminStyle.age(row.ageMs())));
            if (row.metadata() != null && !row.metadata().isBlank()) {
                lore.add("");
                lore.addAll(MenuItems.wrap(MenuItems.MUTED, row.metadata(), 44));
            }
            if (row.strength() < 0) {
                lore.add("");
                lore.add(MenuItems.note("Negative strength: this reduced the risk."));
            }
            items.add(MenuItems.item(row.type().fromModel() ? Material.ENDER_PEARL : Material.COMPARATOR,
                    (row.type().fromModel() ? MenuItems.WARN : MenuItems.LABEL) + row.type().name(), lore));
        }
        return items;
    }

    private List<ItemStack> mitigations(AdminDetailView loaded) {
        List<ItemStack> items = new ArrayList<>();
        List<AdminDetailView.Mitigation> rows = loaded.mitigations();
        for (int i = rows.size() - 1; i >= 0; i--) {
            AdminDetailView.Mitigation row = rows.get(i);
            items.add(MenuItems.item(Material.IRON_BARS, MenuItems.BAD + row.rule(),
                    MenuItems.line("Risk at trigger", AdminStyle.number(row.riskAtTrigger(), 2)),
                    MenuItems.line("State at trigger", row.stateAtTrigger().name(),
                            MenuItems.colourOf(row.stateAtTrigger())),
                    MenuItems.line("Reason", AdminStyle.blankIfEmpty(row.reason(), AdminStyle.NO_DATA)),
                    MenuItems.line("Duration", row.durationMs() + "ms"),
                    MenuItems.line("Remaining", row.remainingMs() > 0 ? row.remainingMs() + "ms" : "expired"),
                    MenuItems.line("Started", AdminStyle.age(row.ageMs()))));
        }
        return items;
    }

    private void requestDetail() {
        var player = gui.tracked(target);
        if (player == null) { detail = null; return; }
        if (!loading.compareAndSet(false, true)) return;
        service().detail(player, result -> {
            detail = result;
            loadedAt = System.currentTimeMillis();
            loading.set(false);
        });
    }

    @Override public void click(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (isFooterClose(slot, rows())) viewer.closeInventory();
        else if (isFooterBack(slot, rows())) back();
    }

    private void back() {
        gui.show(new ProfileMenu(gui, viewer, target, fromSuspicious));
    }
}
