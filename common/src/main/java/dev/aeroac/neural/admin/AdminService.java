package dev.aeroac.neural.admin;

import dev.aeroac.AeroAPI;
import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.locale.AeroMessages;
import dev.aeroac.manager.init.start.StartableInitable;
import dev.aeroac.manager.init.stop.StoppableInitable;
import dev.aeroac.neural.NeuralManager;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.admin.training.DatasetSummary;
import dev.aeroac.neural.admin.training.DatasetSummaryService;
import dev.aeroac.neural.admin.training.HttpTrainingServiceClient;
import dev.aeroac.neural.admin.training.RecordingDraft;
import dev.aeroac.neural.admin.training.TrainingLauncher;
import dev.aeroac.neural.admin.training.TrainingLaunchers;
import dev.aeroac.neural.admin.training.TrainingRequest;
import dev.aeroac.neural.admin.training.TrainingServiceClient;
import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.risk.RiskState;
import dev.aeroac.platform.api.player.PlatformPlayer;
import dev.aeroac.platform.api.scheduler.TaskHandle;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.LogUtil;
import net.kyori.adventure.text.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    /** Where an exported evaluation is picked up from; shown on the model screen. */
    private volatile Path reportDirectory;

    /** Relative to the plugin's data folder. Present by default so the screen has an answer. */
    private static final String DEFAULT_REPORTS = "reports";

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

    @Override public void start() { reload(AeroAPI.INSTANCE.getConfigManager().getConfig()); }

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

        // Enforcement reads the same snapshot the screens read; its policy is reloaded here so
        // a verdict can never be decided under settings that are no longer in force.
        var enforcement = dev.aeroac.neural.enforcement.BanPolicy.read(source);
        AeroAPI.INSTANCE.getBanService().reload(enforcement);
        // Said on every start and reload: whether this server can remove somebody on its own is
        // the one setting an operator must never have to go and look up.
        LogUtil.info("Aero enforcement: " + enforcement.mode()
                + (enforcement.mode() == dev.aeroac.neural.enforcement.BanPolicy.Mode.OFF ? ""
                : ", at " + enforcement.minState() + " with >= " + enforcement.minEvidence()
                + " evidence and >= " + enforcement.minPredictions() + " predictions"
                + (enforcement.automatic() ? ", bans WITHOUT confirmation" : ", staff confirm every ban")));
        reportDirectory = null;
        TrainingServiceClient oldClient = training;
        String reports = trim(source.getStringElse("neural.gui.training.report-directory", DEFAULT_REPORTS));
        training = new TrainingServiceClient.Offline();
        // Two ways to know what training is doing, and they are not exclusive.
        //
        // With neural.training.enabled the service is asked directly: the HTTP client owns the
        // network, polls its own snapshot and answers every getter from cache, so no screen ever
        // waits on it. Separately from inference, which answers questions about the model already
        // running; this one is about the runs that produce one.
        //
        // Without it, the report directory is still read: a server that exports evaluations by hand
        // gets the same screens, one refresh behind, with no service to configure. Whatever was
        // configured before is closed, so a reload never leaves two clients polling.
        String endpoint = trim(source.getStringElse("neural.training.endpoint", ""));
        // mode: local (the default) trains inside the plugin with the Java trainer; mode: service
        // asks the external Python service, as before.
        String mode = trim(source.getStringElse("neural.training.mode", "local")).toLowerCase(java.util.Locale.ROOT);
        boolean localWanted = replacement.enabled() && !"service".equals(mode);
        boolean serviceWanted = replacement.enabled() && !localWanted
                && source.getBooleanElse("neural.training.enabled", false) && !endpoint.isEmpty();
        if (localWanted) {
            if (oldClient instanceof dev.aeroac.neural.admin.training.LocalTrainingServiceClient local && local.running()) {
                // A reload must not kill a run that is minutes into training; it keeps its settings.
                training = local;
            } else {
                try {
                    Path folder = AeroAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath();
                    int cores = Runtime.getRuntime().availableProcessors();
                    training = new dev.aeroac.neural.admin.training.LocalTrainingServiceClient(datasetRoot(),
                            folder.resolve("models"), new dev.aeroac.neural.admin.training.LocalTrainingServiceClient.Options(
                            Math.max(1, Math.min(500, source.getIntElse("neural.training.local.epochs", 30))),
                            Math.max(1, Math.min(64, source.getIntElse("neural.training.local.threads",
                                    Math.max(1, Math.min(4, cores / 2))))),
                            source.getBooleanElse("neural.training.local.include-staff-reviews", true),
                            source.getBooleanElse("neural.training.local.include-review", false),
                            source.getStringElse("neural.training.local.split", "auto")));
                } catch (RuntimeException error) {
                    LogUtil.warn("Aero local training unavailable: " + error.getMessage());
                }
            }
        } else if (serviceWanted) {
            try {
                training = new HttpTrainingServiceClient(endpoint,
                        source.getIntElse("neural.training.timeout-ms", 2000),
                        source.getStringElse("neural.training.token", "").trim());
            } catch (RuntimeException error) {
                LogUtil.warn("Aero training service unusable: " + error.getMessage());
            }
        }
        if (!training.configured() && replacement.enabled() && !reports.isEmpty()) {
            try {
                Path directory = AeroAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath()
                        .resolve(reports).normalize();
                // Created rather than merely watched. An operator who has just run the offline
                // evaluation needs somewhere obvious to put its output, and a directory that only
                // appears once you have already guessed its name is not somewhere obvious.
                java.nio.file.Files.createDirectories(directory);
                reportDirectory = directory;
                training = new dev.aeroac.neural.admin.training.ReportDirectoryClient(directory);
            } catch (RuntimeException | java.io.IOException error) {
                LogUtil.warn("Aero training reports unavailable: " + error.getMessage());
            }
        }
        if (oldClient != training) oldClient.close();

        // Publish what the interface may ask for, and withdraw it when the interface is off. Both are
        // references to live machinery, not copies of it.
        installControls(replacement, source);

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

    /**
     * Hands the screens the two things they may ask for but may not do themselves.
     *
     * <p>Both are installed here and nowhere else, both are refused when the interface is off, and
     * both are withdrawn on stop, so a reload cannot leave a screen holding a control that points at
     * machinery that no longer exists.
     *
     * <p>Nothing in either implementation runs an operation inline. Enabling the runtime writes the
     * canonical configuration and reloads it on the neural manager's own executor; starting a run
     * hands a request to the training client. The screens get a line back, later, from a thread that
     * is not a tick.
     */
    private void installControls(AdminConfig replacement, ConfigManager source) {
        boolean published = replacement.enabled();
        NeuralManager neural = null;
        if (published) {
            try {
                neural = AeroAPI.INSTANCE.getNeuralManager();
            } catch (RuntimeException unavailable) {
                neural = null;
            }
        }
        AdminRuntimeControls.install(neural == null ? null : new RuntimeControl(neural));
        TrainingLaunchers.install(published ? new Launcher(training, source) : null);
    }

    /** The runtime's own controlled switch, seen from the interface. */
    private static final class RuntimeControl implements AdminRuntimeControl {
        private final NeuralManager neural;

        private RuntimeControl(NeuralManager neural) {
            this.neural = neural;
        }

        /**
         * Always available: the manager exists as soon as the plugin does. What may be unavailable is
         * a specific switch, and that answer comes back in the result rather than here.
         */
        @Override public boolean available() { return true; }

        @Override public boolean enabled() { return neural.recordingEnabled(); }

        @Override public String stateDetail() {
            return AeroMessages.tr("gui.runtime.detail", neural.generation());
        }

        @Override public void requestEnable(Consumer<String> reply) { request(true, reply); }

        @Override public void requestDisable(Consumer<String> reply) { request(false, reply); }

        private void request(boolean enable, Consumer<String> reply) {
            CompletableFuture<NeuralManager.EnableResult> pending;
            try {
                pending = enable ? neural.enableRecording() : neural.disableRecording();
            } catch (RuntimeException refused) {
                reply.accept(refused.getMessage());
                return;
            }
            pending.whenComplete((outcome, error) -> {
                if (error != null) {
                    reply.accept(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                    return;
                }
                reply.accept(outcome == null ? null : outcome.message());
                // The switch re-published the snapshot; the screens re-read it on their next refresh.
            });
        }
    }

    /**
     * The training client, seen from the interface.
     *
     * <p>The screen names a preset and nothing else. Everything else the request needs is read here:
     * the dataset from configuration, the window and the head list from the preset the recorder and
     * the inference client already use, and the feature schema version from the encoder, so a run
     * can never be asked for with a shape the dataset does not have.
     */
    private static final class Launcher implements TrainingLauncher {
        private final TrainingServiceClient client;
        private final ConfigManager source;

        private Launcher(TrainingServiceClient client, ConfigManager source) {
            this.client = client;
            this.source = source;
        }

        @Override public boolean available() { return client.configured(); }

        @Override public String dataset() {
            String configured = trim(source.getStringElse("neural.training.dataset", "datasets"));
            return configured.isEmpty() ? "datasets" : configured;
        }

        @Override public void start(String preset, Consumer<String> reply) {
            String window = source.getStringElse("neural.inference." + preset + ".window",
                    "flash".equals(preset) ? "attack" : "continuous");
            TrainingRequest request = new TrainingRequest(dataset(), preset, window, List.of("overall", "aimAssist"),
                    FeatureEncoder.FEATURE_SCHEMA_VERSION, 42L);
            client.start(request, reply);
        }

        @Override public void cancel(Consumer<String> reply) {
            client.cancel(reply);
        }
    }

    /** A configuration read that cannot hand a null to a caller that trims it. */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private void restartTask(AdminConfig replacement) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!replacement.enabled()) return;
        long period = Math.min(replacement.refreshTicks(), replacement.floatingTicks());
        try {
            task = AeroAPI.INSTANCE.getScheduler().getGlobalRegionScheduler()
                    .runAtFixedRate(AeroAPI.INSTANCE.getGrimPlugin(), this::tick, period, period);
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
        for (PlatformPlayer player : AeroAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers()) {
            Published value = published.get(player.getUniqueId(), now, config.refreshMs() * 3L);
            views.add(value == null ? AdminPlayerView.empty(player.getUniqueId(), player.getName(), -1) : value.view());
        }
        AdminSnapshot built = AdminSnapshot.of(views, now);
        snapshot = built;
        dispatchAlerts(built, now);
        dispatchVerdicts(built, now);
    }

    private void requestRefresh() {
        for (AeroPlayer player : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            var ticket = published.begin(player.getUniqueId(), player);
            if (ticket == null) continue;
            player.runSafely(() -> {
                Published result = null;
                try {
                    NeuralRuntime runtime = AeroAPI.INSTANCE.getNeuralManager().runtime();
                    if (running && ticket.generation() == generation() && !player.getNeuralState().disconnected
                            && AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(player.getUniqueId()) == player) {
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

    /**
     * The enforcement pass: one gate per player, on the snapshot that was just published.
     *
     * <p>Kept apart from alerting even though both walk the same list. An alert is a note; this is
     * the path that can remove somebody, and the two should never be one method where a change to
     * the notification accidentally changes who gets banned.
     */
    private void dispatchVerdicts(AdminSnapshot built, long now) {
        var bans = AeroAPI.INSTANCE.getBanService();
        if (bans.policy().mode() == dev.aeroac.neural.enforcement.BanPolicy.Mode.OFF) return;
        bans.expire(now);
        for (AdminPlayerView view : built.players()) {
            var decision = bans.observe(view, now);
            if (decision != null && bans.policy().announces()) {
                bans.announce(decision, AdminPermissions.ENFORCE);
            }
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
        for (PlatformPlayer platform : AeroAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers()) {
            AeroAPI.INSTANCE.getScheduler().getEntityScheduler().execute(platform, AeroAPI.INSTANCE.getGrimPlugin(), () -> {
                if (!running || epoch != generation() || alerts.muted(platform.getUniqueId())
                        || !has(platform, AdminPermissions.ALERTS)) return;
                for (Component message : outgoing) platform.sendMessage(message);
            }, null, 1);
        }
    }

    private final Map<UUID, Object> overlayPending = new ConcurrentHashMap<>();

    private void updateOverlays(AdminConfig settings) {
        long epoch = generation();
        for (AeroPlayer viewer : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            UUID id = viewer.getUniqueId();
            PlatformPlayer platform = viewer.platformPlayer;
            if (platform == null) continue;
            Object token = new Object();
            if (overlayPending.putIfAbsent(id, token) != null) continue;
            AeroAPI.INSTANCE.getScheduler().getEntityScheduler().execute(platform, AeroAPI.INSTANCE.getGrimPlugin(), () -> {
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
    public void detail(AeroPlayer player, Consumer<AdminDetailView> callback) {
        if (player == null) {
            callback.accept(null);
            return;
        }
        long epoch = generation();
        player.runSafely(() -> {
            if (!running || epoch != generation() || player.getNeuralState().disconnected) { callback.accept(null); return; }
            NeuralRuntime runtime = AeroAPI.INSTANCE.getNeuralManager().runtime();
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

    /** The directory an evaluation export is read from, or null when reports are switched off. */
    public Path reportDirectory() { return reportDirectory; }

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
    public void viewMode(AeroPlayer admin, AdminViewMode mode) {
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
    public synchronized void onQuit(AeroPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        published.forget(uuid);
        overlayPending.remove(uuid);
        snapshot = AdminSnapshot.of(snapshot.players().stream().filter(view -> !view.uuid().equals(uuid)).toList(), System.currentTimeMillis());
        alerts.forget(uuid);
        AeroAPI.INSTANCE.getBanService().forget(uuid);
        forgetViewer(uuid);
        for (AeroPlayer viewer : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (viewer != player) viewer.runSafely(() -> overlay.removeTarget(viewer.user, viewer.getUniqueId(), uuid));
        }
    }

    private void clearAllOverlays() {
        for (UUID viewerId : List.copyOf(viewModes.keySet())) {
            AeroPlayer viewer = AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(viewerId);
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
        return AeroAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath().resolve("datasets");
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
        // Withdraw both seams: nothing may ask a stopped interface to switch anything.
        AdminRuntimeControls.install(null);
        TrainingLaunchers.install(null);
    }
}
