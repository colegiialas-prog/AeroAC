package dev.aeroac.platform.bukkit.admin;

import org.bukkit.Bukkit;
import dev.aeroac.neural.enforcement.BanDecision;
import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.admin.AdminDetailView;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.risk.RiskState;
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
    private static final int RISK_TIMELINE_START = 18;
    private static final int SLOT_WATCH = 29;
    private static final int SLOT_PREDICTIONS = 30;
    private static final int SLOT_EVIDENCE = 31;
    private static final int SLOT_FLAGS = 32;
    private static final int SLOT_MITIGATION = 33;
    // Operator actions, one row: go there, watch, hold, look inside, remove, clear.
    private static final int SLOT_MARK = 36;
    private static final int SLOT_TELEPORT = 37;
    private static final int SLOT_SPECTATE = 38;
    private static final int SLOT_FREEZE = 39;
    private static final int SLOT_INVENTORY = 40;
    private static final int SLOT_KICK = 41;
    private static final int SLOT_BAN = 42;
    private static final int SLOT_RESET = 43;

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
        return MenuItems.HEADER + AeroMessages.tr("gui.aero_player") + name;
    }

    @Override public String permission() { return AdminPermissions.PROFILE; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        AdminPlayerView view = service().snapshot().find(target);
        if (view == null) {
            inventory.setItem(SLOT_HEAD, MenuItems.item(Material.BARRIER, MenuItems.BAD + AeroMessages.tr("gui.player_offline"),
                    MenuItems.note(AeroMessages.tr("gui.they_left_while_this_screen_was_open"))));
            footer(inventory, this::back, AeroMessages.tr("gui.the_player_list"));
            return;
        }
        requestDetail();
        AdminDetailView loaded = System.currentTimeMillis() - loadedAt <= service().config().refreshMs() * 3L ? detail : null;

        inventory.setItem(SLOT_HEAD, head(view, loaded));
        drawTimeline(inventory, loaded);
        drawRiskTimeline(inventory, loaded);

        boolean watching = watching();
        inventory.setItem(SLOT_WATCH, MenuItems.item(watching ? Material.REDSTONE_TORCH : Material.TORCH,
                (watching ? MenuItems.BAD + AeroMessages.tr("gui.stop_watch") : MenuItems.HEADER + "WATCH"),
                MenuItems.note(watching
                        ? AeroMessages.tr("gui.stop_streaming_live_telemetry_to_your_chat")
                        : AeroMessages.tr("gui.stream_live_telemetry_for_this_player_to_your_chat"))));

        inventory.setItem(SLOT_PREDICTIONS, MenuItems.item(Material.ENDER_EYE,
                MenuItems.HEADER + AeroMessages.tr("gui.predictions"),
                MenuItems.line(AeroMessages.tr("gui.held"), loaded == null ? "..." : String.valueOf(loaded.predictions().size())),
                MenuItems.line(AeroMessages.tr("gui.accepted"), loaded == null ? "..." : String.valueOf(loaded.trailAccepted())),
                MenuItems.line(AeroMessages.tr("gui.dropped_as_stale"), loaded == null ? "..."
                        : String.valueOf(loaded.trailStaleDropped()))));

        inventory.setItem(SLOT_EVIDENCE, MenuItems.item(Material.BOOK,
                MenuItems.HEADER + AeroMessages.tr("gui.evidence_2"),
                MenuItems.line(AeroMessages.tr("gui.in_the_ring"), String.valueOf(view.evidenceCount())),
                "",
                MenuItems.note(AeroMessages.tr("gui.what_actually_moved_the_risk_value"))));

        inventory.setItem(SLOT_FLAGS, MenuItems.item(Material.COMPARATOR,
                MenuItems.HEADER + AeroMessages.tr("gui.grim_flags"),
                MenuItems.line(AeroMessages.tr("gui.deterministic_evidence"), loaded == null ? "..."
                        : String.valueOf(loaded.grimFlags())),
                "",
                MenuItems.note(AeroMessages.tr("gui.geometry_checks_their_own_violations_and")),
                MenuItems.note(AeroMessages.tr("gui.punishments_are_untouched_by_any_of_this"))));

        inventory.setItem(SLOT_MITIGATION, MenuItems.item(Material.IRON_BARS,
                MenuItems.HEADER + AeroMessages.tr("gui.mitigation_history"),
                MenuItems.line(AeroMessages.tr("gui.recorded"), String.valueOf(view.mitigationCount())),
                MenuItems.line(AeroMessages.tr("gui.active"), view.mitigation() == null ? AeroMessages.tr("gui.none") : view.mitigation(),
                        view.mitigation() == null ? MenuItems.MUTED : MenuItems.BAD)));

        drawActions(inventory, view);
        footer(inventory, this::back, fromSuspicious ? AeroMessages.tr("gui.the_suspicious_list") : AeroMessages.tr("gui.the_player_list"));
    }

    private void drawActions(Inventory inventory, AdminPlayerView view) {
        StaffMarks.Entry mark = gui.marks().get(target);
        List<String> markLore = new ArrayList<>();
        markLore.add(MenuItems.line(AeroMessages.tr("gui.mark.current"), markLabel(mark == null ? StaffMarks.Mark.NONE : mark.mark())));
        if (mark != null) markLore.add(MenuItems.line(AeroMessages.tr("gui.mark.by"), mark.by() + ", " + AdminStyle.age(System.currentTimeMillis() - mark.atMillis())));
        markLore.add("");
        markLore.add(MenuItems.note(AeroMessages.tr("gui.mark.note")));
        markLore.add(MenuItems.note(AeroMessages.tr("gui.mark.click")));
        inventory.setItem(SLOT_MARK, MenuItems.item(Material.NAME_TAG, MenuItems.HEADER + AeroMessages.tr("gui.mark.title"), markLore));
        lockSlot(inventory, SLOT_MARK, AdminPermissions.PROFILE);
        PlayerActions actions = gui.actions();
        boolean spectating = actions.spectating(viewer.getUniqueId());
        boolean frozen = actions.frozen(target);
        inventory.setItem(SLOT_TELEPORT, MenuItems.item(Material.ENDER_PEARL, MenuItems.HEADER + AeroMessages.tr("gui.action.teleport"),
                MenuItems.note(AeroMessages.tr("gui.action.teleport_note"))));
        inventory.setItem(SLOT_SPECTATE, MenuItems.item(Material.COMPASS,
                MenuItems.HEADER + AeroMessages.tr(spectating ? "gui.action.spectate_stop" : "gui.action.spectate"),
                MenuItems.note(AeroMessages.tr("gui.action.spectate_note"))));
        inventory.setItem(SLOT_FREEZE, MenuItems.item(Material.PACKED_ICE,
                (frozen ? MenuItems.BAD : MenuItems.HEADER) + AeroMessages.tr(frozen ? "gui.action.unfreeze" : "gui.action.freeze"),
                MenuItems.note(AeroMessages.tr("gui.action.freeze_note"))));
        inventory.setItem(SLOT_INVENTORY, MenuItems.item(Material.CHEST, MenuItems.HEADER + AeroMessages.tr("gui.action.inventory"),
                MenuItems.note(AeroMessages.tr("gui.action.inventory_note"))));
        inventory.setItem(SLOT_KICK, MenuItems.item(Material.LEATHER_BOOTS, MenuItems.WARN + AeroMessages.tr("gui.action.kick"),
                MenuItems.note(AeroMessages.tr("gui.action.confirm_first"))));
        inventory.setItem(SLOT_BAN, MenuItems.item(Material.IRON_AXE, MenuItems.BAD + AeroMessages.tr("gui.action.ban"),
                MenuItems.line(AeroMessages.tr("gui.risk"), AdminStyle.number(view.risk(), 2)),
                MenuItems.note(AeroMessages.tr("gui.action.ban_note")),
                MenuItems.note(AeroMessages.tr("gui.action.confirm_first"))));
        inventory.setItem(SLOT_RESET, MenuItems.item(Material.MILK_BUCKET, MenuItems.WARN + AeroMessages.tr("gui.action.reset"),
                MenuItems.note(AeroMessages.tr("gui.action.reset_note")),
                MenuItems.note(AeroMessages.tr("gui.action.confirm_first"))));
        lockSlot(inventory, SLOT_TELEPORT, AdminPermissions.ACTION_TELEPORT);
        lockSlot(inventory, SLOT_SPECTATE, AdminPermissions.ACTION_SPECTATE);
        lockSlot(inventory, SLOT_FREEZE, AdminPermissions.ACTION_FREEZE);
        lockSlot(inventory, SLOT_INVENTORY, AdminPermissions.ACTION_INVENTORY);
        lockSlot(inventory, SLOT_KICK, AdminPermissions.ACTION_KICK);
        lockSlot(inventory, SLOT_BAN, AdminPermissions.ENFORCE_CONFIRM);
        lockSlot(inventory, SLOT_RESET, AdminPermissions.ACTION_RESET);
    }

    static String markLabel(StaffMarks.Mark mark) {
        return switch (mark) {
            case NONE -> MenuItems.MUTED + AeroMessages.tr("gui.mark.none");
            case CLEAN -> MenuItems.GOOD + AeroMessages.tr("gui.mark.clean");
            case WATCHING -> MenuItems.WARN + AeroMessages.tr("gui.mark.watching");
            case CHEATER -> MenuItems.BAD + AeroMessages.tr("gui.mark.cheater");
        };
    }

    /** Runs an action against the online target, or says why it cannot. */
    private void act(String permission, java.util.function.Consumer<Player> action) {
        if (!permitted(permission)) {
            deny(permission);
            return;
        }
        Player online = Bukkit.getPlayer(target);
        if (online == null) {
            viewer.sendMessage(MenuItems.BAD + AeroMessages.tr("gui.player_offline"));
            return;
        }
        if (online.getUniqueId().equals(viewer.getUniqueId())) {
            viewer.sendMessage(MenuItems.BAD + AeroMessages.tr("gui.action.not_yourself"));
            return;
        }
        action.accept(online);
    }

    private void kick(Player online) {
        confirm("gui.action.kick", "gui.action.kick_question", new Object[]{online.getName()}, "gui.action.kick",
                List.of(), () -> {
                    Player still = Bukkit.getPlayer(target);
                    if (still != null) still.kickPlayer(AeroMessages.tr("gui.action.kick_reason"));
                }, () -> new ProfileMenu(gui, viewer, target, fromSuspicious));
    }

    private void ban(Player online) {
        AdminPlayerView view = service().snapshot().find(target);
        if (view == null) return;
        long now = System.currentTimeMillis();
        BanDecision decision = new BanDecision(BanDecision.nextId(target, now), target, online.getName(),
                view.state() == null ? RiskState.CLEAN : view.state(), view.risk(), view.overall(),
                view.dominant() == null ? null : view.dominant().label(), view.evidenceCount(), view.predictionCount(), now);
        confirm("gui.action.ban", "gui.action.ban_question", new Object[]{online.getName()}, "gui.action.ban",
                List.of(MenuItems.line(AeroMessages.tr("gui.risk"), AdminStyle.number(view.risk(), 2)),
                        MenuItems.riskLine(view.overall())),
                () -> AeroAPI.INSTANCE.getBanService().carryOut(decision, viewer.getName()),
                () -> new ProfileMenu(gui, viewer, target, fromSuspicious));
    }

    private void reset(Player online) {
        confirm("gui.action.reset", "gui.action.reset_question", new Object[]{online.getName()}, "gui.action.reset",
                List.of(), () -> {
                    var player = gui.tracked(target);
                    var runtime = gui.neural().runtime();
                    if (player == null || runtime == null) return;
                    player.runSafely(() -> runtime.resetRisk(target, player.getNeuralState()));
                    viewer.sendMessage(MenuItems.GOOD + AeroMessages.tr("gui.action.reset_done", online.getName()));
                }, () -> new ProfileMenu(gui, viewer, target, fromSuspicious));
    }

    private ItemStack head(AdminPlayerView view, AdminDetailView loaded) {
        RiskState state = view.state() == null ? RiskState.CLEAN : view.state();
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.state"), AdminLabels.state(state), MenuItems.colourOf(state)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.risk"), AdminStyle.number(view.risk(), 2)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.peak_risk"), AdminStyle.number(view.peakRisk(), 2)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.in_this_state"), AdminStyle.duration(view.stateForSeconds())));
        String cheat = cheatLine(view.risk());
        if (cheat != null) {
            lore.add(cheat);
            lore.add(MenuItems.note(AeroMessages.tr("gui.cheat_probability_note")));
        }
        lore.add("");
        lore.add(MenuItems.riskLine(view.overall()));
        lore.add(MenuItems.headLine("Aim Assist", view.aimAssist()));
        lore.add(MenuItems.headLine("KillAura", view.killAura()));
        lore.add(MenuItems.headLine("TriggerBot", view.triggerBot()));
        if (view.hasPrediction()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.model"), view.model() + " " + view.modelVersion()
                    + (view.calibrated() ? "" : AeroMessages.tr("gui.uncalibrated"))));
            lore.add(MenuItems.line(AeroMessages.tr("gui.age"), AdminStyle.age(view.predictionAgeMs())));
        }
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.ping"), view.ping() < 0 ? AdminStyle.NO_DATA : view.ping() + "ms"));
        lore.add(MenuItems.line(AeroMessages.tr("gui.combat"), AdminStyle.duration(view.combatSeconds())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.current_target"),
                view.targetEntityId() < 0 ? AeroMessages.tr("gui.none") : String.valueOf(view.targetEntityId())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.aim_error"), AdminStyle.number(view.aimError(), 2)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.delta_yaw_pitch"), AdminStyle.number(view.deltaYaw(), 2)
                + " / " + AdminStyle.number(view.deltaPitch(), 2)));
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.evidence"), String.valueOf(view.evidenceCount())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.predictions_2"), String.valueOf(view.predictionCount())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.mitigation"), view.mitigation() == null ? AeroMessages.tr("gui.none")
                : view.mitigation() + " (" + view.mitigationRemainingMs() + AeroMessages.tr("gui.ms_left_2"),
                view.mitigation() == null ? MenuItems.MUTED : MenuItems.BAD));
        if (loaded == null) {
            lore.add("");
            lore.add(MenuItems.note(AeroMessages.tr("gui.loading_history_from_the_player_s_connection")));
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
                        MenuItems.MUTED + (loaded == null ? AeroMessages.tr("gui.loading") : AdminStyle.NO_DATA)));
                continue;
            }
            double value = values[i];
            inventory.setItem(slot, MenuItems.item(bandOf(value),
                    bandColour(value) + AdminStyle.percent(value),
                    MenuItems.note(AdminStyle.RISK_LABEL + ", " + (values.length - i) + AeroMessages.tr("gui.predictions_ago"))));
        }
    }

    /**
     * Nine panes of accumulated risk, one per recent piece of evidence, oldest on the left, coloured
     * by the state that risk puts the player in. This is how the verdict was reached, step by step.
     */
    private void drawRiskTimeline(Inventory inventory, AdminDetailView loaded) {
        double[] values = loaded == null ? new double[0] : loaded.riskTimeline();
        var runtime = gui.neural().runtime();
        var engine = runtime == null ? null : runtime.riskEngine();
        for (int i = 0; i < 9; i++) {
            int slot = RISK_TIMELINE_START + i;
            if (i >= values.length) {
                inventory.setItem(slot, MenuItems.item(Material.BLACK_STAINED_GLASS_PANE,
                        MenuItems.MUTED + (loaded == null ? AeroMessages.tr("gui.loading") : AdminStyle.NO_DATA)));
                continue;
            }
            RiskState state = engine == null ? RiskState.CLEAN : engine.stateFor(values[i]);
            List<String> lore = new ArrayList<>();
            lore.add(MenuItems.line(AeroMessages.tr("gui.state"), AdminLabels.state(state), MenuItems.colourOf(state)));
            String cheat = cheatLine(values[i]);
            if (cheat != null) lore.add(cheat);
            lore.add(MenuItems.note(AeroMessages.tr("gui.risk_after_evidence", values.length - i)));
            inventory.setItem(slot, MenuItems.item(stateGlass(state),
                    MenuItems.colourOf(state) + AeroMessages.tr("gui.risk") + AdminStyle.number(values[i], 2), lore));
        }
    }

    private static Material stateGlass(RiskState state) {
        return switch (state) {
            case CLEAN -> Material.LIME_STAINED_GLASS_PANE;
            case WATCH -> Material.YELLOW_STAINED_GLASS_PANE;
            case SUSPICIOUS, MITIGATED -> Material.ORANGE_STAINED_GLASS_PANE;
            case CONFIRMED -> Material.RED_STAINED_GLASS_PANE;
        };
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
            case SLOT_MARK -> {
                StaffMarks.Mark now = gui.marks().cycle(target, viewer.getName());
                viewer.sendMessage(MenuItems.HEADER + AeroMessages.tr("gui.mark.set", markLabel(now)));
                redraw();
            }
            case SLOT_TELEPORT -> act(AdminPermissions.ACTION_TELEPORT, online -> {
                viewer.closeInventory();
                gui.actions().teleport(viewer, online);
            });
            case SLOT_SPECTATE -> act(AdminPermissions.ACTION_SPECTATE, online -> {
                viewer.closeInventory();
                gui.actions().toggleSpectate(viewer, online);
            });
            case SLOT_FREEZE -> act(AdminPermissions.ACTION_FREEZE, online -> {
                gui.actions().toggleFreeze(viewer, online);
                redraw();
            });
            case SLOT_INVENTORY -> act(AdminPermissions.ACTION_INVENTORY, online -> viewer.openInventory(online.getInventory()));
            case SLOT_KICK -> act(AdminPermissions.ACTION_KICK, this::kick);
            case SLOT_BAN -> act(AdminPermissions.ENFORCE_CONFIRM, this::ban);
            case SLOT_RESET -> act(AdminPermissions.ACTION_RESET, this::reset);
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
