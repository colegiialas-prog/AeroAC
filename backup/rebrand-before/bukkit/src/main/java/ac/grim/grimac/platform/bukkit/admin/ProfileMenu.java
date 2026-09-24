package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.admin.AdminDetailView;
import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminPlayerView;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.risk.RiskState;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything known about one player, on one screen.
 *
 * <p>The summary comes from the published snapshot and is always there. The deep history behind the
 * buttons is fetched from the player's own event loop and arrives a moment later; until it does the
 * screen says so rather than showing zeroes, because on this screen in particular a zero that means
 * "not loaded yet" is indistinguishable from a zero that means "no evidence".
 */
public final class ProfileMenu extends AeroMenu {
    private static final int SLOT_HEAD = 4;
    private static final int TIMELINE_START = 9;
    private static final int SLOT_WATCH = 29;
    private static final int SLOT_PREDICTIONS = 30;
    private static final int SLOT_EVIDENCE = 31;
    private static final int SLOT_FLAGS = 32;
    private static final int SLOT_MITIGATION = 33;

    private final UUID target;
    private final boolean fromSuspicious;
    private volatile AdminDetailView detail;
    private final java.util.concurrent.atomic.AtomicBoolean loading = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long loadedAt;

    public ProfileMenu(BukkitAdminGui gui, Player viewer, UUID target, boolean fromSuspicious) {
        super(gui, viewer);
        this.target = target;
        this.fromSuspicious = fromSuspicious;
    }

    @Override protected String title() {
        AdminPlayerView view = service().snapshot().find(target);
        String name = view == null ? "?" : view.name();
        return MenuItems.HEADER + "AERO › PLAYER › " + name;
    }

    @Override public String permission() { return AdminPermissions.PROFILE; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        AdminPlayerView view = service().snapshot().find(target);
        if (view == null) {
            inventory.setItem(SLOT_HEAD, MenuItems.item(Material.BARRIER, MenuItems.BAD + "PLAYER OFFLINE",
                    MenuItems.note("They left while this screen was open.")));
            footer(inventory, this::back, "the player list");
            return;
        }
        requestDetail();
        AdminDetailView loaded = System.currentTimeMillis() - loadedAt <= service().config().refreshMs() * 3L ? detail : null;

        inventory.setItem(SLOT_HEAD, head(view, loaded));
        drawTimeline(inventory, loaded);

        boolean watching = watching();
        inventory.setItem(SLOT_WATCH, MenuItems.item(watching ? Material.REDSTONE_TORCH : Material.TORCH,
                (watching ? MenuItems.BAD + "STOP WATCH" : MenuItems.HEADER + "WATCH"),
                MenuItems.note(watching
                        ? "Stop streaming live telemetry to your chat."
                        : "Stream live telemetry for this player to your chat.")));

        inventory.setItem(SLOT_PREDICTIONS, MenuItems.item(Material.ENDER_EYE,
                MenuItems.HEADER + "PREDICTIONS",
                MenuItems.line("Held", loaded == null ? "..." : String.valueOf(loaded.predictions().size())),
                MenuItems.line("Accepted", loaded == null ? "..." : String.valueOf(loaded.trailAccepted())),
                MenuItems.line("Dropped as stale", loaded == null ? "..."
                        : String.valueOf(loaded.trailStaleDropped()))));

        inventory.setItem(SLOT_EVIDENCE, MenuItems.item(Material.BOOK,
                MenuItems.HEADER + "EVIDENCE",
                MenuItems.line("In the ring", String.valueOf(view.evidenceCount())),
                "",
                MenuItems.note("What actually moved the risk value.")));

        inventory.setItem(SLOT_FLAGS, MenuItems.item(Material.COMPARATOR,
                MenuItems.HEADER + "GRIM FLAGS",
                MenuItems.line("Deterministic evidence", loaded == null ? "..."
                        : String.valueOf(loaded.grimFlags())),
                "",
                MenuItems.note("Geometry checks. Their own violations and"),
                MenuItems.note("punishments are untouched by any of this.")));

        inventory.setItem(SLOT_MITIGATION, MenuItems.item(Material.IRON_BARS,
                MenuItems.HEADER + "MITIGATION HISTORY",
                MenuItems.line("Recorded", String.valueOf(view.mitigationCount())),
                MenuItems.line("Active", view.mitigation() == null ? "none" : view.mitigation(),
                        view.mitigation() == null ? MenuItems.MUTED : MenuItems.BAD)));

        footer(inventory, this::back, fromSuspicious ? "the suspicious list" : "the player list");
    }

    private ItemStack head(AdminPlayerView view, AdminDetailView loaded) {
        RiskState state = view.state() == null ? RiskState.CLEAN : view.state();
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line("State", state.name(), MenuItems.colourOf(state)));
        lore.add(MenuItems.line("Risk", AdminStyle.number(view.risk(), 2)));
        lore.add(MenuItems.line("Peak risk", AdminStyle.number(view.peakRisk(), 2)));
        lore.add(MenuItems.line("In this state", AdminStyle.duration(view.stateForSeconds())));
        lore.add("");
        lore.add(MenuItems.riskLine(view.overall()));
        lore.add(MenuItems.headLine("Aim Assist", view.aimAssist()));
        lore.add(MenuItems.headLine("KillAura", view.killAura()));
        lore.add(MenuItems.headLine("TriggerBot", view.triggerBot()));
        if (view.hasPrediction()) {
            lore.add(MenuItems.line("Model", view.model() + " " + view.modelVersion()
                    + (view.calibrated() ? "" : " (uncalibrated)")));
            lore.add(MenuItems.line("Age", AdminStyle.age(view.predictionAgeMs())));
        }
        lore.add("");
        lore.add(MenuItems.line("Ping", view.ping() < 0 ? AdminStyle.NO_DATA : view.ping() + "ms"));
        lore.add(MenuItems.line("Combat", AdminStyle.duration(view.combatSeconds())));
        lore.add(MenuItems.line("Current target",
                view.targetEntityId() < 0 ? "none" : String.valueOf(view.targetEntityId())));
        lore.add(MenuItems.line("Aim error", AdminStyle.number(view.aimError(), 2)));
        lore.add(MenuItems.line("Delta yaw/pitch", AdminStyle.number(view.deltaYaw(), 2)
                + " / " + AdminStyle.number(view.deltaPitch(), 2)));
        lore.add("");
        lore.add(MenuItems.line("Evidence", String.valueOf(view.evidenceCount())));
        lore.add(MenuItems.line("Predictions", String.valueOf(view.predictionCount())));
        lore.add(MenuItems.line("Mitigation", view.mitigation() == null ? "none"
                : view.mitigation() + " (" + view.mitigationRemainingMs() + "ms left)",
                view.mitigation() == null ? MenuItems.MUTED : MenuItems.BAD));
        if (loaded == null) {
            lore.add("");
            lore.add(MenuItems.note("Loading history from the player's connection..."));
        }
        return MenuItems.head(view.uuid(), view.name(),
                MenuItems.colourOf(state) + AdminStyle.symbol(state) + " " + view.name(), lore);
    }

    /**
     * Nine panes of recent model output, oldest on the left.
     *
     * <p>The bands are a reading aid and nothing else: they are fixed quarters of the output range,
     * not the risk engine's thresholds, and a pane turning red is not a decision about the player.
     */
    private void drawTimeline(Inventory inventory, AdminDetailView loaded) {
        double[] values = loaded == null ? new double[0] : loaded.timeline();
        for (int i = 0; i < 9; i++) {
            int slot = TIMELINE_START + i;
            if (i >= values.length) {
                inventory.setItem(slot, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE,
                        MenuItems.MUTED + (loaded == null ? "loading" : AdminStyle.NO_DATA)));
                continue;
            }
            double value = values[i];
            inventory.setItem(slot, MenuItems.item(bandOf(value),
                    bandColour(value) + AdminStyle.percent(value),
                    MenuItems.note(AdminStyle.RISK_LABEL + ", " + (values.length - i) + " predictions ago")));
        }
    }

    private static Material bandOf(double value) {
        if (Double.isNaN(value)) return Material.GRAY_STAINED_GLASS_PANE;
        if (value < 0.25) return Material.LIME_STAINED_GLASS_PANE;
        if (value < 0.50) return Material.YELLOW_STAINED_GLASS_PANE;
        if (value < 0.75) return Material.ORANGE_STAINED_GLASS_PANE;
        return Material.RED_STAINED_GLASS_PANE;
    }

    private static String bandColour(double value) {
        if (Double.isNaN(value)) return MenuItems.MUTED;
        if (value < 0.25) return MenuItems.GOOD;
        if (value < 0.50) return "§e";
        if (value < 0.75) return MenuItems.WARN;
        return MenuItems.BAD;
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

    private boolean watching() {
        var runtime = gui.neural().runtime();
        return runtime != null && runtime.monitor().watching(target, viewer.getUniqueId());
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
            case SLOT_WATCH -> toggleWatch(target);
            case SLOT_PREDICTIONS -> gui.show(new HistoryMenu(gui, viewer, target,
                    HistoryMenu.Kind.PREDICTIONS, fromSuspicious));
            case SLOT_EVIDENCE -> gui.show(new HistoryMenu(gui, viewer, target,
                    HistoryMenu.Kind.EVIDENCE, fromSuspicious));
            case SLOT_FLAGS -> gui.show(new HistoryMenu(gui, viewer, target,
                    HistoryMenu.Kind.GRIM_FLAGS, fromSuspicious));
            case SLOT_MITIGATION -> {
                if (permitted(AdminPermissions.MITIGATION)) {
                    gui.show(new HistoryMenu(gui, viewer, target, HistoryMenu.Kind.MITIGATION, fromSuspicious));
                } else {
                    deny(AdminPermissions.MITIGATION);
                }
            }
            default -> { }
        }
    }

    private void back() {
        gui.show(new PlayerListMenu(gui, viewer, fromSuspicious, 0));
    }
}
