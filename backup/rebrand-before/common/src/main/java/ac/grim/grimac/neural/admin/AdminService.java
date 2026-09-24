package ac.grim.grimac.neural.admin;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.manager.init.stop.StoppableInitable;
import ac.grim.grimac.neural.NeuralRuntime;
import ac.grim.grimac.neural.admin.training.DatasetSummary;
import ac.grim.grimac.neural.admin.training.DatasetSummaryService;
import ac.grim.grimac.neural.admin.training.RecordingDraft;
import ac.grim.grimac.neural.admin.training.TrainingServiceClient;
import ac.grim.grimac.neural.risk.RiskState;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.scheduler.TaskHandle;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import net.kyori.adventure.text.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Keeps one published snapshot of neural state for the administrator interface, and nothing else.
 *
 * <p>The loop is deliberately one-directional. Every refresh asks each player's own event loop to
 * build an immutable view of that player, those views are collected into a snapshot, and every
 * screen, alert and name tag is rendered from that snapshot. No screen reaches into the risk
 * engine, no click takes a lock, and nothing on this path can write to neural state: the interface
 * is a reader, and a reader that stalls can only ever make itself late.
 *
 * <p>The published snapshot is at most one refresh old, because a refresh requested on this tick
 * lands on the player loops asynchronously and is read on the next one. That is the price of never
 * touching event-loop-confined state from anywhere else, and at a one-second refresh it is
 * invisible to an operator.
 */
public final class AdminService implements StartableInitable, StoppableInitable {
    private volatile AdminConfig config = AdminConfig.read(emptyConfigFallback());
    private volatile AdminSnapshot snapshot = AdminSnapshot.EMPTY;
    private volatile DatasetSummaryService summaries;
    private volatile TrainingServiceClient training = new TrainingServiceClient.Offline();
    private volatile AdminGuiBridge gui = AdminGuiBridge.UNAVAILABLE;

    /** Views published by player event loops, read by the refresh task. */
    private record Published(AdminPlayerView view, int entityId) { }
    private final SnapshotMailbox<Published> published = new SnapshotMailbox<>();
    private volatile boolean running;
    public long generation() { return published.generation(); }
    private final Map<UUID, AdminViewMode> viewModes = new ConcurrentHashMap<>();
    private final Map<UUID, RecordingDraft> drafts = new ConcurrentHashMap<>();
    private final AdminAlerts alerts = new AdminAlerts();
    private final NameplateOverlay overlay = new NameplateOverlay();

    private TaskHandle task;
    private long lastSnapshotMillis;
    private long lastFloatingMillis;

    @Override public void start() { reload(GrimAPI.INSTANCE.getConfigManager().getConfig()); }

    /**
     * Rebuilds the settings and drops everything derived from the previous ones.
     *
     * <p>Indicators are cleared with packets before the state is dropped, so a reload cannot leave a
     * name tag on an administrator's screen that nothing owns any more.
     */
    public synchronized void reload(ConfigManager source) {
        AdminConfig replacement = AdminConfig.read(source);
        running = false;
        published.reset();
        gui.closeAll();
        clearAllOverlays();
        overlayPending.clear();
        alerts.reset();
        drafts.clear();

        snapshot = AdminSnapshot.EMPTY;
        config = replacement;
        lastSnapshotMillis = lastFloatingMillis = 0;
        running = replacement.enabled();
        gui.reload();

        DatasetSummaryService previous = summaries;
        if (previous != null) previous.close();
        summaries = replacement.enabled() ? new DatasetSummaryService(datasetRoot(),
                replacement.dataset().cacheSeconds(), replacement.dataset().maxSessions(),
                replacement.dataset().recentSessions()) : null;

        TrainingServiceClient oldClient = training;
        String reports = source.getStringElse("neural.gui.training.report-directory", "").trim();
        training = new TrainingServiceClient.Offline();
        if (replacement.enabled() && !reports.isEmpty()) {
            try {
                training = new ac.grim.grimac.neural.admin.training.ReportDirectoryClient(
                        GrimAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath().resolve(reports).normalize());
            } catch (RuntimeException error) {
                LogUtil.warn("Aero training reports unavailable: " + error.getMessage());
            }
        }
        oldClient.close();

        // A view mode an operator chose survives a reload: it is their setting, not configuration.
        // A mode that is no longer permitted is dropped on the next refresh anyway.
        restartTask(replacement);
        if (replacement.enabled()) {
            LogUtil.info("Aero admin interface ready: /aero, refresh " + replacement.refreshMs() + "ms, "
                    + "indicators " + (replacement.floating().enabled()
                    ? replacement.floating().refreshMs() + "ms" : "off")
                    + ". Monitoring only; it changes no detection behaviour.");
        }
    }

    private void restartTask(AdminConfig replacement) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!replacement.enabled()) return;
        long period = Math.min(replacement.refreshTicks(), replacement.floatingTicks());
        try {
            task = GrimAPI.INSTANCE.getScheduler().getGlobalRegionScheduler()
                    .runAtFixedRate(GrimAPI.INSTANCE.getGrimPlugin(), this::tick, period, period);
        } catch (RuntimeException error) {
            LogUtil.warn("Aero admin interface could not schedule its refresh: " + error.getMessage());
        }
    }

    /**
     * One refresh. Publishes what the player loops produced last time, then asks for the next round.
     *
     * <p>Runs on the global scheduler. Everything expensive here is bounded by the number of online
     * players, and the only work done per player is copying values out of an object that the player
     * loop already finished building.
     */
    private synchronized void tick() {
        AdminConfig settings = config;
        if (!running || !settings.enabled()) return;
        long now = System.currentTimeMillis();

        if (now - lastSnapshotMillis >= settings.refreshMs()) {
            lastSnapshotMillis = now;
            publishSnapshot(now);
            requestRefresh();
        }
        if (settings.floating().enabled() && now - lastFloatingMillis >= settings.floating().refreshMs()) {
            lastFloatingMillis = now;
            updateOverlays(settings);
        }
    }

    private void publishSnapshot(long now) {
        List<AdminPlayerView> views = new ArrayList<>();
        for (PlatformPlayer player : GrimAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers()) {
            Published value = published.get(player.getUniqueId(), now, config.refreshMs() * 3L);
            views.add(value == null ? AdminPlayerView.empty(player.getUniqueId(), player.getName(), -1) : value.view());
        }
        AdminSnapshot built = AdminSnapshot.of(views, now);
        snapshot = built;
        dispatchAlerts(built, now);
    }

    private void requestRefresh() {
        for (GrimPlayer player : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            var ticket = published.begin(player.getUniqueId(), player);
            if (ticket == null) continue;
            player.runSafely(() -> {
                Published result = null;
                try {
                    NeuralRuntime runtime = GrimAPI.INSTANCE.getNeuralManager().runtime();
                    if (running && ticket.generation() == generation() && !player.getNeuralState().disconnected
                            && GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(player.getUniqueId()) == player) {
                        boolean watched = runtime != null && runtime.monitor().watching(player.getUniqueId());
                        result = new Published(AdminViews.summary(player, runtime, System.nanoTime(), watched), player.entityID);
                    }
                } catch (RuntimeException unavailable) {
                    // A failed diagnostic view must not interrupt packet processing.
                } finally {
                    published.complete(ticket, result, System.currentTimeMillis());
                }
            });
        }
    }

    private void dispatchAlerts(AdminSnapshot built, long now) {
        AdminConfig.Alerts settings = config.alerts();
        if (!settings.enabled()) return;
        List<Component> messages = null;
        for (AdminPlayerView view : built.players()) {
            AdminAlerts.Alert alert = alerts.observe(view, settings, now);
            if (alert == null) continue;
            if (messages == null) messages = new ArrayList<>(2);
            messages.add(AdminAlerts.render(alert));
        }
        if (messages == null) return;
        List<Component> outgoing = List.copyOf(messages);
        long epoch = generation();
        for (PlatformPlayer platform : GrimAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers()) {
            GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(platform, GrimAPI.INSTANCE.getGrimPlugin(), () -> {
                if (!running || epoch != generation() || alerts.muted(platform.getUniqueId())
                        || !has(platform, AdminPermissions.ALERTS)) return;
                for (Component message : outgoing) platform.sendMessage(message);
            }, null, 1);
        }
    }

    private final Map<UUID, Object> overlayPending = new ConcurrentHashMap<>();

    private void updateOverlays(AdminConfig settings) {
        long epoch = generation();
        for (GrimPlayer viewer : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            UUID id = viewer.getUniqueId();
            PlatformPlayer platform = viewer.platformPlayer;
            if (platform == null) continue;
            Object token = new Object();
            if (overlayPending.putIfAbsent(id, token) != null) continue;
            GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(platform, GrimAPI.INSTANCE.getGrimPlugin(), () -> {
                if (!running || epoch != generation()) { overlayPending.remove(id, token); return; }
                boolean permitted = has(platform, AdminPermissions.VIEW);
                boolean recording = has(platform, AdminPermissions.TRAINING);
                if (permitted) viewModes.putIfAbsent(id, defaultViewMode());
                AdminViewMode mode = permitted ? viewMode(id) : AdminViewMode.OFF;
                viewer.runSafely(() -> {
                    try {
                        if (!running || epoch != generation() || viewer.getNeuralState().disconnected) return;
                        if (mode == AdminViewMode.OFF) { overlay.clearViewer(viewer.user, id); return; }
                        List<NameplateOverlay.Target> targets = new ArrayList<>();
                        long now = System.currentTimeMillis();
                        for (AdminPlayerView candidate : snapshot.players()) {
                            Published value = published.get(candidate.uuid(), now, settings.refreshMs() * 3L);
                            if (value != null && ((recording && candidate.recording() != null) || mode.shows(candidate.state()))) {
                                targets.add(new NameplateOverlay.Target(candidate, value.entityId()));
                            }
                        }
                        overlay.update(viewer, targets, recording);
                        if (!running || epoch != generation()) overlay.clearViewer(viewer.user, id);
                    } finally { overlayPending.remove(id, token); }
                });
            }, () -> overlayPending.remove(id, token), 1);
        }
    }

    /**
     * Builds the deep history for one player, on that player's event loop.
     *
     * <p>The callback runs on that loop, so a platform that needs a different thread to open an
     * inventory has to hop; that hop is the platform's business and is done in the GUI layer.
     */
    public void detail(GrimPlayer player, Consumer<AdminDetailView> callback) {
        if (player == null) {
            callback.accept(null);
            return;
        }
        long epoch = generation();
        player.runSafely(() -> {
            if (!running || epoch != generation() || player.getNeuralState().disconnected) { callback.accept(null); return; }
            NeuralRuntime runtime = GrimAPI.INSTANCE.getNeuralManager().runtime();
            boolean watched = runtime != null && runtime.monitor().watching(player.getUniqueId());
            AdminDetailView detail;
            try {
                detail = AdminViews.detail(player, runtime, System.nanoTime(), watched);
            } catch (RuntimeException error) {
                detail = null;
            }
            callback.accept(epoch == generation() && !player.getNeuralState().disconnected ? detail : null);
        });
    }

    public AdminSnapshot snapshot() { return snapshot; }

    public AdminConfig config() { return config; }

    public AdminAlerts alerts() { return alerts; }

    public NameplateOverlay overlay() { return overlay; }

    public TrainingServiceClient training() { return training; }

    public AdminGuiBridge gui() { return gui; }

    /** Registered once by the platform that can actually draw screens. */
    public void gui(AdminGuiBridge bridge) { this.gui = bridge == null ? AdminGuiBridge.UNAVAILABLE : bridge; }

    /** Never blocks: returns the cached dataset summary, refreshed on a background thread. */
    public DatasetSummary datasetSummary() {
        DatasetSummaryService service = summaries;
        return service == null ? DatasetSummary.PENDING : service.snapshot();
    }

    public void invalidateDatasetSummary() {
        DatasetSummaryService service = summaries;
        if (service != null) service.invalidate();
    }

    public AdminViewMode viewMode(UUID admin) {
        return viewModes.getOrDefault(admin, AdminViewMode.OFF);
    }

    /** Setting OFF clears the indicators immediately rather than waiting for the next refresh. */
    public void viewMode(GrimPlayer admin, AdminViewMode mode) {
        if (admin == null) return;
        if (mode == AdminViewMode.OFF) {
            viewModes.put(admin.getUniqueId(), AdminViewMode.OFF);
            admin.runSafely(() -> overlay.clearViewer(admin.user, admin.getUniqueId()));
        } else {
            viewModes.put(admin.getUniqueId(), mode);
        }
    }

    public AdminViewMode defaultViewMode() { return config.floating().defaultMode(); }

    public RecordingDraft draft(UUID admin) {
        return drafts.computeIfAbsent(admin, id -> new RecordingDraft());
    }

    public RecordingDraft peekDraft(UUID admin) { return drafts.get(admin); }

    public void clearDraft(UUID admin) { drafts.remove(admin); }

    /** Also called for platform players excluded from Grim's packet tracking. */
    public void forgetViewer(UUID uuid) {
        alerts.forgetViewer(uuid);
        drafts.remove(uuid);
        viewModes.remove(uuid);
        overlayPending.remove(uuid);
        overlay.forget(uuid);
    }

    /** Called for every disconnect: a player is both a possible target and a possible viewer. */
    public synchronized void onQuit(GrimPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        published.forget(uuid);
        overlayPending.remove(uuid);
        snapshot = AdminSnapshot.of(snapshot.players().stream().filter(view -> !view.uuid().equals(uuid)).toList(), System.currentTimeMillis());
        alerts.forget(uuid);
        forgetViewer(uuid);
        for (GrimPlayer viewer : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (viewer != player) viewer.runSafely(() -> overlay.removeTarget(viewer.user, viewer.getUniqueId(), uuid));
        }
    }

    private void clearAllOverlays() {
        for (UUID viewerId : List.copyOf(viewModes.keySet())) {
            GrimPlayer viewer = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(viewerId);
            if (viewer != null) viewer.runSafely(() -> overlay.clearViewer(viewer.user, viewerId));
            else overlay.forget(viewerId);
        }
    }

    private static boolean has(PlatformPlayer player, String permission) {
        return player.hasPermission(permission) || player.hasPermission(AdminPermissions.ADMIN);
    }

    /** Convenience for screens: how many players sit at or above a state in the live snapshot. */
    public int count(RiskState state) { return snapshot.count(state); }

    private static Path datasetRoot() {
        return GrimAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath().resolve("datasets");
    }

    /**
     * Settings used before the first reload, so a screen opened during start-up is not a null.
     *
     * <p>Every getter answers with the caller's fallback, which is exactly the default configuration.
     */
    private static ConfigManager emptyConfigFallback() {
        return (ConfigManager) java.lang.reflect.Proxy.newProxyInstance(
                AdminService.class.getClassLoader(), new Class<?>[]{ConfigManager.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasLoaded")) return false;
                    if (args != null && args.length == 2) return args[1];
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0d;
                    if (type == java.util.List.class) return java.util.List.of();
                    if (type == java.util.Map.class) return java.util.Map.of();
                    return null;
                });
    }

    @Override public synchronized void stop() {
        running = false;
        published.reset();
        overlayPending.clear();
        snapshot = AdminSnapshot.EMPTY;
        alerts.reset();
        gui.closeAll();
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearAllOverlays();
        viewModes.clear();
        published.reset();
        drafts.clear();
        DatasetSummaryService service = summaries;
        summaries = null;
        if (service != null) service.close();
        TrainingServiceClient client = training;
        training = new TrainingServiceClient.Offline();
        if (client != null) client.close();
    }
}
