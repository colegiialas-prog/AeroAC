package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.manager.init.stop.StoppableInitable;
import ac.grim.grimac.neural.admin.AdminGuiBridge;
import ac.grim.grimac.neural.admin.AdminService;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.scheduler.TaskHandle;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import ac.grim.grimac.platform.bukkit.admin.training.RecordingsMenu;
import ac.grim.grimac.platform.bukkit.admin.training.TrainingMenu;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every open Aero screen on this server and keeps them current.
 *
 * <p>Screens are opened, redrawn and closed on the thread that owns the player, which on Folia is
 * their region and everywhere else is the main thread. The data they render was published by the
 * admin service on an entirely different schedule, so a redraw is a copy out of a finished snapshot
 * and never waits for anything.
 *
 * <p>The open-screen map exists for two reasons the inventory holder cannot cover: knowing which
 * players to schedule a redraw for without touching every online player's open window from the
 * wrong thread, and being able to close every screen on reload.
 */
public final class BukkitAdminGui implements AdminGuiBridge, StartableInitable, StoppableInitable {
    private final Map<UUID, Open> open = new ConcurrentHashMap<>();
    private TaskHandle refreshTask;
    private volatile long generation;
    private volatile boolean running;

    private record Open(AeroMenu menu, PlatformPlayer platform, java.util.concurrent.atomic.AtomicBoolean pending) {
        Open(AeroMenu menu, PlatformPlayer platform) {
            this(menu, platform, new java.util.concurrent.atomic.AtomicBoolean());
        }
    }

    public AdminService service() { return GrimAPI.INSTANCE.getAdminService(); }

    public ac.grim.grimac.neural.NeuralManager neural() { return GrimAPI.INSTANCE.getNeuralManager(); }
    public GrimPlayer tracked(UUID uuid) { return GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid); }
    public Sender sender(UUID uuid) {
        PlatformPlayer platform = platformFor(uuid);
        return platform == null ? null : platform.getSender();
    }

    @Override public void start() {
        if (org.bukkit.Material.matchMaterial("BELL") == null) {
            LogUtil.warn("Aero inventories require Bukkit 1.14+. Text commands remain available.");
            return;
        }
        running = true;
        Bukkit.getPluginManager().registerEvents(new MenuListener(this), GrimACBukkitLoaderPlugin.LOADER);
        service().gui(this);
        schedule();
    }

    @Override public boolean available() { return running; }

    @Override public void resumeRecording(Sender sender, String field) {
        with(sender, player -> show(new ac.grim.grimac.platform.bukkit.admin.training.WizardMenu(this, player,
                ac.grim.grimac.platform.bukkit.admin.training.WizardMenu.resumeStep(field,
                        service().draft(player.getUniqueId())))));
    }

    @Override public void reload() { closeAll(); if (running) schedule(); }

    @Override public void openMain(Sender sender) {
        with(sender, player -> show(new MainMenu(this, player)));
    }

    @Override public void openPlayers(Sender sender, boolean suspiciousOnly) {
        with(sender, player -> show(new PlayerListMenu(this, player, suspiciousOnly, 0)));
    }

    @Override public void openProfile(Sender sender, UUID target) {
        with(sender, player -> show(new ProfileMenu(this, player, target, false)));
    }

    @Override public void openTraining(Sender sender) {
        with(sender, player -> show(new TrainingMenu(this, player)));
    }

    @Override public void openRecordings(Sender sender) {
        with(sender, player -> show(new RecordingsMenu(this, player)));
    }

    /** Resolves the Bukkit player behind a command sender; the console reaches no screen. */
    private void with(Sender sender, java.util.function.Consumer<Player> action) {
        var platform = sender.getPlatformPlayer();
        Object nativePlayer = platform == null ? null : platform.getNative();
        if (nativePlayer instanceof Player player) action.accept(player);
    }

    /**
     * Restarts the redraw loop at the configured interval.
     *
     * <p>Called on reload as well as on start, because the interval is configuration and an operator
     * who lowers it should not have to restart the server to see the effect.
     */
    public void schedule() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        long period = Math.max(1L, service().config().refreshTicks());
        try {
            refreshTask = GrimAPI.INSTANCE.getScheduler().getGlobalRegionScheduler()
                    .runAtFixedRate(GrimAPI.INSTANCE.getGrimPlugin(), this::refreshAll, period, period);
        } catch (RuntimeException error) {
            LogUtil.warn("Aero admin screens will not auto-refresh: " + error.getMessage());
        }
    }

    /** Opens a screen and takes ownership of it, replacing whatever the viewer had open. */
    public void show(AeroMenu menu) {
        long expected = generation;
        Player viewer = menu.viewer;
        PlatformPlayer platform = platformFor(viewer.getUniqueId());
        if (platform == null || !running) return;
        Runnable task = () -> {
            if (!running || expected != generation || !viewer.isOnline()) return;
            if (!menu.allowed()) { menu.deny(menu.permission()); return; }
            try {
                menu.open();
                open.put(viewer.getUniqueId(), new Open(menu, platform));
            } catch (RuntimeException error) {
                viewer.sendMessage(MenuItems.BAD + "Aero could not open that screen: " + error.getMessage());
                LogUtil.warn("Aero admin screen failed to open: " + error);
            }
        };
        GrimAPI.INSTANCE.getScheduler().getEntityScheduler()
                .execute(platform, GrimAPI.INSTANCE.getGrimPlugin(), task, null, 1);
    }

    /** Called by the listener when a viewer closes an Aero window. */
    public void forget(UUID viewer) {
        open.remove(viewer);
    }

    public void forget(UUID viewer, AeroMenu menu) {
        Open state = open.get(viewer);
        if (state != null && state.menu() == menu) open.remove(viewer, state);
    }

    public AeroMenu current(UUID viewer) {
        Open entry = open.get(viewer);
        return entry == null ? null : entry.menu();
    }

    private void refreshAll() {
        if (open.isEmpty()) return;
        for (Map.Entry<UUID, Open> entry : open.entrySet()) {
            Open state = entry.getValue();
            Player viewer = state.menu().viewer;
            if (!state.pending().compareAndSet(false, true)) continue;
            Runnable redraw = () -> {
                try {
                // The viewer may have closed or navigated away between the schedule and the run.
                Open still = open.get(entry.getKey());
                if (still == null || still.menu() != state.menu()) return;
                if (!viewer.isOnline()) { open.remove(entry.getKey(), state); return; }
                if (!state.menu().allowed()) { viewer.closeInventory(); return; }
                if (viewer.getOpenInventory().getTopInventory().getHolder() != state.menu()) return;
                if (!state.menu().live()) return;
                try {
                    state.menu().redraw();
                } catch (RuntimeException error) {
                    LogUtil.warn("Aero admin screen failed to refresh: " + error);
                    open.remove(entry.getKey());
                }
                } finally { state.pending().set(false); }
            };
            GrimAPI.INSTANCE.getScheduler().getEntityScheduler()
                    .execute(state.platform(), GrimAPI.INSTANCE.getGrimPlugin(), redraw,
                            () -> open.remove(entry.getKey(), state), 1);
        }
    }

    /** Closes every Aero screen. Used on reload so no screen survives the state it was built from. */
    @Override public void closeAll() {
        generation++;
        for (Open state : Map.copyOf(open).values()) {
            Player viewer = state.menu().viewer;
            open.remove(viewer.getUniqueId(), state);
            PlatformPlayer platform = state.platform();
            Runnable close = () -> {
                if (viewer.getOpenInventory().getTopInventory().getHolder() == state.menu()) {
                    viewer.closeInventory();
                }
            };
            if (GrimAPI.INSTANCE.getPlatform() != ac.grim.grimac.platform.api.Platform.FOLIA && Bukkit.isPrimaryThread()) close.run();
            else GrimAPI.INSTANCE.getScheduler().getEntityScheduler()
                    .execute(platform, GrimAPI.INSTANCE.getGrimPlugin(), close, null, 1);
        }
    }

    private static PlatformPlayer platformFor(UUID uuid) {
        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid);
        if (player != null && player.platformPlayer != null) return player.platformPlayer;
        try {
            return GrimAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(uuid);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @Override public void stop() {
        running = false;
        closeAll();
        service().gui(null);
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        open.clear();
    }
}
