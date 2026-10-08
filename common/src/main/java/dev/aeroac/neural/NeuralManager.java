package dev.aeroac.neural;

import dev.aeroac.AeroAPI;
import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.locale.AeroMessages;
import dev.aeroac.manager.init.start.StartableInitable;
import dev.aeroac.manager.init.stop.StoppableInitable;
import dev.aeroac.neural.dataset.DatasetManager;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.neural.inference.HttpInferenceClient;
import dev.aeroac.neural.inference.InferenceClient;
import dev.aeroac.neural.telemetry.CombatTelemetryCollector;
import dev.aeroac.neural.telemetry.ReachObservation;
import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Entry point for every hook the anticheat calls. Telemetry starts at a player's first attack and
 * stops again when combat goes quiet, so a connection that never fights costs one empty state object.
 *
 * <p>Nothing here can flag a check or punish. The only gameplay-affecting action is the optional,
 * default-off attack cancellation in Phase 6, applied after the packet has already been observed.
 *
 * <h2>Generations and the published snapshot</h2>
 * Every reload — including one that ends with the module disabled — increments the generation and
 * publishes a new immutable {@link NeuralSnapshot}. Readers (the administrator interface, commands,
 * diagnostics) read that snapshot instead of the individual fields, so they can never observe a
 * half-applied reload, and every asynchronous callback can answer "is the configuration I started
 * with still the current one?" by comparing generations. Callbacks that outlive their generation are
 * abandoned rather than applied.
 *
 * <h2>Where heavy work happens</h2>
 * Disk and network work never runs on a player's event loop or on the server tick: session files are
 * created and written by {@code DatasetManager}'s own worker, inference is an HTTP client with its
 * own pool, and the controlled enable/disable call below does its file write and its reload on a
 * dedicated background executor.
 */
public final class NeuralManager implements StartableInitable, StoppableInitable {
    private volatile NeuralConfig config;
    private volatile NeuralRuntime runtime;
    private volatile DatasetManager datasets;
    private volatile NeuralSnapshot snapshot = NeuralSnapshot.INITIAL;
    private long generations;
    private long lastServerTickNanos;
    private volatile double tickDurationMs = Double.NaN;
    private volatile long serverTick;

    private final Supplier<Path> dataFolder;
    private final NeuralLog log;
    private final AtomicBoolean enableInFlight = new AtomicBoolean();
    private volatile Executor ioExecutor;

    public NeuralManager() {
        this(NeuralManager::platformDataFolder, NeuralLog.PLATFORM);
    }

    /** Used by tests: a data folder and a log without a server behind them. */
    NeuralManager(Supplier<Path> dataFolder, NeuralLog log) {
        this.dataFolder = dataFolder == null ? () -> null : dataFolder;
        this.log = log == null ? NeuralLog.SILENT : log;
    }

    @Override public void start() { reload(AeroAPI.INSTANCE.getConfigManager().getConfig()); }

    /**
     * Rebuilds everything a configuration generation owns and publishes it.
     *
     * <p>The generation advances even when the result is "disabled": a reader that saw generation 4
     * enabled must be able to tell that generation 5 turned it off, and a callback still in flight
     * from generation 4 must be discarded. The previous runtime is closed after the new snapshot is
     * in place, so no hook can observe the manager without a runtime while a runtime exists.
     */
    public synchronized void reload(ConfigManager source) {
        NeuralConfig replacement = NeuralConfig.read(source == null ? disabledConfig() : source);
        NeuralRuntime previous = runtime;
        long generation = ++generations;
        config = replacement;
        DatasetManager manager = datasets;
        try {
            if (manager != null && manager.closed()) manager = null;
            if (manager != null) {
                manager.update(replacement);
            } else if (needsDisk(replacement)) {
                Path root = datasetsRoot();
                if (root == null) throw new IOException("папка данных недоступна");
                datasets = manager = new DatasetManager(root, replacement, log::warn);
            }
        } catch (Exception error) {
            log.error("Хранилище датасетов Aero недоступно; детерминированные проверки продолжают работу.", error);
        }
        try {
            runtime = replacement.telemetryEnabled()
                    ? new NeuralRuntime(generation, replacement, buildClient(replacement), this::datasets,
                            riskStore(replacement))
                    : null;
        } catch (Exception error) {
            runtime = null;
            log.error("Нейро-рантайм Aero отключён из-за ошибки конфигурации; детерминированные проверки продолжают работу.", error);
        }
        snapshot = NeuralSnapshot.of(generation, replacement, runtime, configPath(), datasetsPath(),
                System.currentTimeMillis());
        // One line per startup/reload: the absolute file that produced this state and what it says.
        log.info("Aero AC: конфигурация " + configPath() + " | " + snapshot.flags()
                + ", generation=" + generation);
        if (previous != null) previous.close();
    }

    private static boolean needsDisk(NeuralConfig settings) {
        return settings.enabled() && (settings.collectionEnabled() || settings.risk().enabled());
    }

    /** One store for the manager's lifetime, so reloads never lose remembered risk. */
    private dev.aeroac.neural.risk.RiskStore riskStore;

    private dev.aeroac.neural.risk.RiskStore riskStore(NeuralConfig settings) {
        if (!settings.risk().enabled() || settings.risk().persistHours() <= 0) return null;
        if (riskStore == null) {
            Path folder = dataFolder();
            if (folder == null) return null;
            riskStore = new dev.aeroac.neural.risk.RiskStore(folder.resolve("neural").resolve("risk-store.json"), log::warn);
        }
        return riskStore;
    }

    private InferenceClient buildClient(NeuralConfig settings) throws IOException {
        if (!settings.inference().enabled()) return null;
        if (settings.inference().local()) return buildLocalClient(settings.inference());
        return new HttpInferenceClient(settings.inference().endpoint(), settings.inference().timeoutMs(),
                settings.inference().maxInFlight(), Math.min(4, Math.max(1, settings.inference().maxInFlight())));
    }

    /**
     * Loads the configured bundles for in-JVM inference. A bundle that is missing or built for another
     * feature schema throws, which disables the runtime with a logged reason instead of serving a model
     * that would read the wrong channels.
     */
    private InferenceClient buildLocalClient(NeuralConfig.Inference inference) throws IOException {
        java.util.List<dev.aeroac.neural.inference.local.LocalModelBundle> bundles = new java.util.ArrayList<>();
        bundles.add(loadBundle(inference.flashBundle(), dev.aeroac.neural.inference.ModelKind.FLASH));
        if (inference.proEnabled() && !inference.proBundle().isEmpty()) {
            bundles.add(loadBundle(inference.proBundle(), dev.aeroac.neural.inference.ModelKind.PRO));
        }
        return new dev.aeroac.neural.inference.local.LocalInferenceClient(bundles, inference.maxInFlight(),
                inference.localThreads());
    }

    private dev.aeroac.neural.inference.local.LocalModelBundle loadBundle(String configured,
                                                                          dev.aeroac.neural.inference.ModelKind kind) throws IOException {
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            Path folder = dataFolder();
            if (folder == null) throw new IOException("папка данных недоступна для " + configured);
            path = folder.resolve(configured);
        }
        var bundle = dev.aeroac.neural.inference.local.LocalModelBundle.load(path);
        if (bundle.kind() != kind) {
            throw new IOException(path + " содержит модель " + bundle.kind().wireName() + ", ожидалась " + kind.wireName());
        }
        log.info("Aero AC: локальная модель " + kind.wireName() + " " + bundle.modelVersion() + " из " + path
                + (bundle.calibrated() ? "" : " (без калибровки)"));
        return bundle;
    }

    public void serverTick() {
        long now = System.nanoTime();
        if (lastServerTickNanos != 0) tickDurationMs = (now - lastServerTickNanos) / 1_000_000.0;
        lastServerTickNanos = now;
        serverTick++;
    }

    public double serverTickDurationMs() { return tickDurationMs; }
    public long serverTickNumber() { return serverTick; }
    public DatasetManager datasets() { return datasets; }
    public NeuralRuntime runtime() { return runtime; }
    public NeuralConfig config() { return config; }

    /** The immutable state of the current generation. Safe to read from any thread, including a GUI. */
    public NeuralSnapshot snapshot() { return snapshot; }

    /** The current generation: it advances on every reload, enabled or not. */
    public long generation() { return snapshot.generation(); }

    /** True when both recording switches are on in the current generation. */
    public boolean recordingEnabled() { return snapshot.recordingEnabled(); }

    /** The live collector, or null when telemetry is off or this player is not in combat. */
    public CombatTelemetryCollector collector(AeroPlayer player) {
        NeuralPlayerState state = player.getNeuralState();
        NeuralRuntime active = runtime;
        if (state.disconnected || active == null) {
            if (state.collector != null) state.resetRuntime();
            return null;
        }
        CombatTelemetryCollector collector = state.collector;
        if (collector != null && collector.generation() != active.generation()) {
            // A reload replaced window sizes and thresholds; derived state from the old one is dropped.
            state.resetRuntime();
            return null;
        }
        return collector;
    }

    /**
     * Out of combat: keep only the last few rotations, primitive and in place, so a first attack
     * has the history an aimbot's snap onto its target happens in. Teleports, vehicles and cancelled
     * ticks break it the same way they break a collector's segment.
     */
    private void recordHistory(AeroPlayer player, PacketReceiveEvent event, NeuralRuntime active) {
        NeuralPlayerState state = player.getNeuralState();
        if (state.disconnected || !active.config().telemetryEnabled()) return;
        try {
            int capacity = Math.max(1, active.config().attackBefore());
            if (state.history == null || state.history.capacity() != capacity) {
                state.history = new dev.aeroac.neural.window.RotationHistory(capacity);
            }
            if ((WrapperPlayClientPlayerFlying.isFlying(event.getPacketType()) && player.packetStateData.lastPacketWasTeleport)
                    || player.inVehicle()) {
                state.history.clear();
            } else if (player.packetEntityReplication.isTickPacket(event.getPacketType()) && !event.isCancelled()) {
                CombatTelemetryCollector.recordHistory(player, state.history, System.nanoTime());
            }
        } catch (RuntimeException error) { failed(player, error); }
    }

    /**
     * Creates the collector on demand. Called on the first attack and when recording starts; it continues
     * from the rotation history kept while the player was not in combat.
     */
    private CombatTelemetryCollector openCollector(AeroPlayer player, long nowNanos) {
        NeuralRuntime active = runtime;
        if (active == null || player.getNeuralState().disconnected) return null;
        CombatTelemetryCollector collector = collector(player);
        if (collector == null) {
            collector = new CombatTelemetryCollector(player, active.config(), active.generation(), nowNanos);
            NeuralPlayerState state = player.getNeuralState();
            if (state.history != null) {
                collector.seed(state.history.frames(1, nowNanos));
                state.history.clear();
            }
            state.collector = collector;
        }
        return collector;
    }

    // Called immediately before PacketEntityReplication advances compensated interpolation.
    public void beforeEntityReplication(AeroPlayer player, PacketReceiveEvent event) {
        CombatTelemetryCollector collector = collector(player);
        NeuralRuntime active = runtime;
        if (collector == null) {
            if (active != null) recordHistory(player, event, active);
            return;
        }
        if (active == null) return;
        try {
            long now = System.nanoTime();
            if (collector.idle(now, active.config().idleTimeoutSeconds() * 1_000_000_000L)) {
                player.getNeuralState().collector = null;
                return;
            }
            if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType()) && player.packetStateData.lastPacketWasTeleport) {
                collector.discontinuity("teleport");
            } else if (player.packetEntityReplication.isTickPacket(event.getPacketType()) && !event.isCancelled()) {
                collector.sample(event);
                active.afterSample(player, player.getNeuralState(), collector, System.nanoTime());
            }
        } catch (RuntimeException error) { failed(player, error); }
    }

    public void packet(AeroPlayer player, PacketReceiveEvent event) {
        NeuralRuntime active = runtime;
        if (active == null) return;
        try {
            long now = System.nanoTime();
            if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
                attack(player, new WrapperPlayClientAttack(event).getEntityId(), event, now);
            } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
                WrapperPlayClientInteractEntity interaction = new WrapperPlayClientInteractEntity(event);
                if (interaction.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    attack(player, interaction.getEntityId(), event, now);
                }
            } else if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
                CombatTelemetryCollector collector = collector(player);
                if (collector != null) collector.swing(event.isCancelled(), now);
            }
        } catch (RuntimeException error) { failed(player, error); }
    }

    /**
     * Telemetry records the packet as the rest of the anticheat saw it, and only then may Phase 6
     * cancel it, so a mitigated hit is never recorded as if a deterministic check had rejected it.
     */
    private void attack(AeroPlayer player, int entityId, PacketReceiveEvent event, long now) {
        NeuralRuntime active = runtime;
        CombatTelemetryCollector collector = openCollector(player, now);
        if (collector != null) collector.attack(entityId, event.isCancelled(), now);
        if (active != null && !event.isCancelled() && active.shouldCancelAttack(player.getNeuralState(), now)) {
            event.setCancelled(true);
            if (player.getNeuralState().mitigation != null) player.getNeuralState().mitigation.suppressedOne();
        }
    }

    public void flag(AeroPlayer player, String name) {
        // These check families run on inbound packet processing. Ignore unrelated/outbound checks.
        if (name == null || !(name.equals("Reach") || name.equals("WallHit") || name.equals("EntityPierce") || name.startsWith("PacketOrder"))) return;
        NeuralRuntime active = runtime;
        if (active == null) return;
        try {
            CombatTelemetryCollector collector = collector(player);
            if (collector != null) collector.flag(name);
            active.onCheckFlag(player, player.getNeuralState(), name, System.nanoTime());
        } catch (RuntimeException error) { failed(player, error); }
    }

    public void reach(AeroPlayer player, int id, double distance, int intersection, int los, SimpleCollisionBox box) {
        CombatTelemetryCollector collector = collector(player);
        if (collector != null) {
            try {
                collector.reach(new ReachObservation(System.nanoTime(), id, distance, intersection, los,
                        box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
            } catch (RuntimeException error) { failed(player, error); }
        }
    }

    public void discontinuity(AeroPlayer player, String reason) {
        CombatTelemetryCollector collector = collector(player);
        if (collector != null) {
            try { collector.discontinuity(reason); } catch (RuntimeException error) { failed(player, error); }
        }
    }

    private void failed(AeroPlayer player, RuntimeException error) {
        NeuralPlayerState state = player.getNeuralState();
        state.stop("TELEMETRY_ERROR");
        state.resetRuntime();
        log.error("Телеметрия Aero остановлена для одного игрока; детерминированные проверки продолжают работу.", error);
    }

    /**
     * Opens a recording session for one player.
     *
     * <p>Caller must be on the player's event loop; the disk open completes asynchronously back on
     * that loop, and the acknowledgement is sent only after the session has its raw file, its
     * metadata file, its writer and its registration. Every reason the start cannot happen is
     * reported as itself — storage not open, configuration not loaded, module off, collection off,
     * IO failure with the absolute folder path — because a single combined "disabled" message is
     * exactly what makes an operator unable to tell an unreadable disk from a config flag.
     *
     * <p>The generation captured here is re-checked when the disk answers: a start that spanned a
     * reload is cancelled instead of recording into a configuration that no longer exists.
     */
    public void startSession(AeroPlayer player, DatasetMetadata.Label label, String family, String client,
                             String configuration, String scenario, String assistStrength, String notes,
                             Consumer<String> reply) {
        if (label == DatasetMetadata.Label.CHEAT) {
            // Quick recordings name only the cheat. Without a client and a configuration the audit
            // sends the session to REVIEW and training silently leaves it out, so say "not given"
            // explicitly instead of leaving the fields empty.
            if (client == null || client.isBlank()) client = "unspecified";
            if (configuration == null || configuration.isBlank()) configuration = "default";
        }
        NeuralPlayerState state = player.getNeuralState();
        DatasetManager manager = datasets;
        NeuralConfig settings = config;
        NeuralRuntime active = runtime;
        if (manager == null) {
            reply.accept(tr("cmd.aero.dataset_storage_is_not_open",
                    "Хранилище датасетов не открыто. Папка: " + datasetsPath()
                            + ". Проверьте консоль и права на запись."));
            return;
        }
        if (settings == null) {
            reply.accept(tr("cmd.aero.neural_configuration_is_not_loaded",
                    "Конфигурация Aero ещё не загружена. Выполните перезагрузку конфигурации."));
            return;
        }
        if (!settings.enabled()) {
            reply.accept(tr("cmd.aero.neural_module_is_disabled",
                    "Нейросеть выключена: neural.enabled=false в " + configPath()
                            + ". Включите её и перезагрузите конфигурацию."));
            return;
        }
        if (active == null || !settings.telemetryEnabled()) {
            reply.accept(tr("cmd.aero.neural_telemetry_is_disabled_deterministic_checks_are_unaffe",
                    "Нейро-телеметрия отключена. Включите хотя бы одного потребителя "
                            + "(neural.collection.enabled, neural.inference.enabled или neural.risk.enabled) "
                            + "и перезагрузите конфигурацию."));
            return;
        }
        if (!settings.recordingEnabled()) {
            reply.accept(tr("cmd.aero.dataset_recording_is_disabled",
                    "Запись датасетов выключена: neural.collection.enabled=false. "
                            + "Включите neural.enabled и neural.collection.enabled, затем перезагрузите конфигурацию."));
            return;
        }
        if (state.disconnected || state.opening || (state.session != null && !state.session.completed())) {
            reply.accept(tr("cmd.aero.dataset_session_already_open",
                    "Сессия уже открыта, открывается или ещё закрывается; проверьте состояние датасета."));
            return;
        }
        DatasetMetadata.LabelSource source = switch (label) {
            case LEGIT -> DatasetMetadata.LabelSource.LAB_LEGIT;
            case CHEAT -> DatasetMetadata.LabelSource.LAB_CHEAT;
            case UNLABELED -> DatasetMetadata.LabelSource.PRODUCTION_UNLABELED;
        };
        DatasetMetadata metadata;
        try {
            DatasetMetadata.AssistStrength strength = DatasetMetadata.AssistStrength.parse(assistStrength);
            if (strength == null && assistStrength != null && !assistStrength.isBlank()) {
                reply.accept(tr("cmd.aero.unknown_assist_strength", "Неизвестная сила помощи "
                        + assistStrength + "; допустимые значения: " + DatasetMetadata.AssistStrength.names()));
                return;
            }
            metadata = new DatasetMetadata(UUID.randomUUID(), manager.pseudonym(player.getUniqueId()),
                    System.currentTimeMillis(), System.nanoTime(), label, source, family, client, configuration,
                    scenario, strength, notes,
                    player.getClientVersion().getProtocolVersion(), AeroAPI.INSTANCE.getExternalAPI().getGrimVersion(),
                    settings.continuousSize(), settings.attackBefore(), settings.attackAfter());
        } catch (RuntimeException error) {
            reply.accept(tr("cmd.aero.dataset_metadata_failed", "Не удалось подготовить метаданные сессии: "
                    + describe(error)));
            return;
        }
        long generation = active.generation();
        state.opening = true;
        state.cancelOpening = false;
        manager.open(metadata).whenComplete((session, error) -> {
            // Publish before scheduling: a channel can close without executing the queued callback.
            if (session != null) {
                state.session = session;
                if (state.disconnected || state.cancelOpening) session.close("START_CANCELLED");
            }
            player.runSafely(() -> {
                state.opening = false;
                if (error != null) {
                    reply.accept(openFailure(error));
                    return;
                }
                // Generation guard: the runtime object identity alone would miss a reload that
                // rebuilt an equal runtime, and would not tell the operator why the start was dropped.
                if (state.disconnected || state.cancelOpening || runtime != active
                        || snapshot.generation() != generation || !session.accepting()) {
                    session.close("START_CANCELLED");
                    reply.accept(tr("gui.training.start_cancelled",
                            "Запуск записи отменён: игрок отключился, конфигурация перезагружена или запись остановлена."));
                    return;
                }
                state.session = session;
                CombatTelemetryCollector collector = openCollector(player, System.nanoTime());
                if (collector == null) {
                    session.close("START_CANCELLED");
                    reply.accept(tr("cmd.aero.dataset_recording_is_disabled",
                            "Запись датасетов выключена: нейро-телеметрия больше не активна. "
                                    + "Включите neural.enabled и neural.collection.enabled, затем перезагрузите конфигурацию."));
                    return;
                }
                collector.attach(session);
                reply.accept(tr("gui.training.recording_started", "Запись Aero AC запущена: сессия "
                        + metadata.sessionId() + ", метка " + label + ", поколение " + generation + "."));
            });
        });
    }

    /** Adds or removes a live watcher. Returns an operator-facing description of the outcome. */
    public String monitor(AeroPlayer player, Sender sender, boolean enable) {
        NeuralRuntime active = runtime;
        if (active == null) {
            return tr("cmd.aero.neural_telemetry_is_disabled_deterministic_checks_are_unaffe",
                    "Нейро-телеметрия отключена; наблюдать нечего.");
        }
        NeuralPlayerState state = player.getNeuralState();
        boolean changed = enable
                ? active.monitor().add(player.getUniqueId(), sender)
                : active.monitor().remove(player.getUniqueId(), sender);
        state.monitored = active.monitor().watching(player.getUniqueId());
        if (!changed) {
            return enable
                    ? tr("cmd.aero.monitor_limit_reached", "Достигнут предел наблюдателей для этого игрока.")
                    : tr("cmd.aero.monitor_not_watching", "Вы не наблюдали за этим игроком.");
        }
        return (enable ? "Наблюдение включено: " : "Наблюдение выключено: ") + player.getName()
                + " (наблюдателей=" + active.monitor().watcherCount(player.getUniqueId()) + ")";
    }

    public void disconnect(AeroPlayer player) {
        NeuralPlayerState state = player.getNeuralState();
        state.disconnected = true;
        state.stop("DISCONNECT");
        NeuralRuntime active = runtime;
        if (active != null) {
            active.monitor().clear(player.getUniqueId());
            active.park(player.getUniqueId(), state.risk, System.nanoTime());
        }
        state.resetRuntime();
    }

    /**
     * Stops everything: the runtime, then the disk worker, which drains every admitted session and
     * writes its final metadata before the executor goes away. The generation advances one last time
     * and the published snapshot says so, so a screen or a command that reads it after stop sees a
     * stopped module rather than the state it had a moment earlier.
     */
    @Override public synchronized void stop() {
        NeuralRuntime active = runtime;
        runtime = null;
        long generation = ++generations;
        if (active != null) {
            // A restart must not be a reset: remember everyone still online before the runtime goes.
            try {
                long now = System.nanoTime();
                for (AeroPlayer player : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
                    active.remember(player.getUniqueId(), player.getNeuralState().risk, now);
                }
            } catch (RuntimeException | LinkageError unavailable) {
                // LinkageError: without a platform (tests, a broken start) AeroAPI cannot even initialise.
                log.warn("Aero AC: риск онлайн-игроков не сохранён при остановке: " + unavailable);
            }
            active.close();
        }
        if (riskStore != null) {
            riskStore.close();
            riskStore = null;
        }
        DatasetManager manager = datasets;
        datasets = null;
        // Stop runs on the server thread during plugin disable, so it may not wait for the disk: the
        // worker keeps draining in the background (its own deadline, daemon thread) and the caller
        // gets a bounded wait instead of a five-second stall.
        if (manager != null && !manager.drainAndClose(500, TimeUnit.MILLISECONDS)) {
            log.warn("Очередь датасетов дописывается в фоне после остановки: " + datasetsPath());
        }
        snapshot = NeuralSnapshot.stopped(generation, config, configPath(), datasetsPath(),
                System.currentTimeMillis());
        log.info("Aero AC: остановлено, generation=" + generation
                + ", датасеты=" + datasetsPath());
    }

    // ---------------------------------------------------------------------------------------------
    // Controlled enable/disable for the administrator interface
    // ---------------------------------------------------------------------------------------------

    /**
     * Outcome of one controlled switch request. {@code generation} is the generation in effect after
     * the request finished, so a caller can pin the state it is about to render.
     */
    public record EnableResult(boolean ok, boolean requested, boolean recordingEnabled, long generation,
                               String message) { }

    /**
     * Turns recording on or off on behalf of the administrator interface.
     *
     * <p>Controlled means: both {@code neural.enabled} and {@code neural.collection.enabled} are
     * written to the canonical {@code config.yml} together (recording is their conjunction, so one
     * flag alone would leave the interface claiming an on state the module does not have), the write
     * is atomic, the canonical reload path then re-reads the file and rebuilds every consumer, and
     * the result is only reported as successful when the published snapshot actually agrees with the
     * request. One request runs at a time; a second one is refused instead of racing.
     *
     * <p>The file write and the reload run on a dedicated background executor, never on the server
     * tick or a player's event loop, and the caller gets a future it can complete its own screen with.
     */
    public CompletableFuture<EnableResult> setRecordingEnabled(boolean enabled) {
        if (!enableInFlight.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new EnableResult(false, enabled, recordingEnabled(),
                    generation(), tr("gui.training.switch_already_running",
                    "Операция включения или выключения уже выполняется; дождитесь её завершения.")));
        }
        CompletableFuture<EnableResult> result = new CompletableFuture<>();
        try {
            ioExecutor().execute(() -> {
                EnableResult outcome;
                try {
                    outcome = applyRecordingFlags(enabled);
                } catch (Throwable error) {
                    outcome = new EnableResult(false, enabled, recordingEnabled(), generation(),
                            "Не удалось изменить состояние записи: " + describe(error));
                } finally {
                    enableInFlight.set(false);
                }
                result.complete(outcome);
            });
        } catch (RejectedExecutionException rejected) {
            enableInFlight.set(false);
            result.complete(new EnableResult(false, enabled, recordingEnabled(), generation(),
                    "Не удалось запустить операцию изменения записи: " + describe(rejected)));
        }
        return result;
    }

    /** Convenience for a GUI "start recording" control. */
    public CompletableFuture<EnableResult> enableRecording() { return setRecordingEnabled(true); }

    /** Convenience for a GUI "stop recording" control. */
    public CompletableFuture<EnableResult> disableRecording() { return setRecordingEnabled(false); }

    private EnableResult applyRecordingFlags(boolean enabled) {
        AeroAPI api;
        ConfigManager canonical;
        try {
            api = AeroAPI.INSTANCE;
            canonical = api == null || api.getConfigManager() == null ? null : api.getConfigManager().getConfig();
        } catch (Throwable unavailable) {
            api = null;
            canonical = null;
        }
        if (api == null || canonical == null) {
            return new EnableResult(false, enabled, false, generation(), tr("cmd.aero.neural_configuration_is_not_loaded",
                    "Конфигурация Aero ещё не загружена; изменить состояние записи невозможно."));
        }
        Path file = configFile();
        if (file == null) {
            return new EnableResult(false, enabled, false, generation(),
                    "Папка данных Aero недоступна; изменить состояние записи невозможно.");
        }
        try {
            String before = Files.readString(file);
            String after = dev.aeroac.manager.config.NeuralFlagsFile.apply(before, enabled);
            if (after == null) {
                return new EnableResult(false, enabled, false, generation(),
                        "Не удалось изменить " + file + ": файл не удалось разобрать как конфигурацию.");
            }
            if (!after.equals(before)) {
                dev.aeroac.manager.config.NeuralFlagsFile.writeAtomically(file, after);
            }
        } catch (IOException error) {
            return new EnableResult(false, enabled, false, generation(),
                    "Не удалось сохранить флаги в " + file + ": " + describe(error));
        }

        boolean reloaded;
        try {
            // The canonical path: re-read the file just written, re-run the updater, rebuild every
            // consumer, and bump the generation so every reader sees a new snapshot.
            reloaded = api.getExternalAPI().reloadAsync(canonical).get(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new EnableResult(false, enabled, recordingEnabled(), generation(),
                    "Изменение состояния записи прервано; проверьте конфигурацию " + file + ".");
        } catch (ExecutionException | java.util.concurrent.TimeoutException failed) {
            Throwable cause = failed instanceof ExecutionException ? failed.getCause() : failed;
            return new EnableResult(false, enabled, recordingEnabled(), generation(),
                    "Флаги записаны в " + file + ", но перезагрузка не удалась: " + describe(cause));
        }
        NeuralSnapshot published = snapshot;
        if (!reloaded || published.recordingEnabled() != enabled) {
            return new EnableResult(false, enabled, published.recordingEnabled(), published.generation(),
                    "Флаги записаны в " + file + ", но состояние записи не совпало с запрошенным ("
                            + published.flags() + "). Проверьте конфигурацию и консоль.");
        }
        return new EnableResult(true, enabled, true, published.generation(), enabled
                ? tr("gui.training.recording_enabled", "Запись Aero AC включена (neural.enabled=true, "
                        + "neural.collection.enabled=true, generation=" + published.generation() + ").")
                : tr("gui.training.recording_disabled", "Запись Aero AC выключена (neural.enabled=false, "
                        + "neural.collection.enabled=false, generation=" + published.generation() + ")."));
    }

    /** plugins/AeroAC/models, where trained bundles and backups live; null when there is no data folder. */
    public Path modelsRoot() {
        Path folder = dataFolder();
        return folder == null ? null : folder.resolve("models");
    }

    /** The folder the local Flash model is loaded from, as configured. */
    public Path activeModelPath() {
        Path path = Path.of(config.inference().flashBundle().isEmpty() ? "models/flash" : config.inference().flashBundle());
        if (path.isAbsolute()) return path;
        Path folder = dataFolder();
        return folder == null ? null : folder.resolve(path);
    }

    /**
     * Switches the server to a trained model in one step: copies models/trained/&lt;name&gt; into the
     * configured flash-bundle folder (keeping the old one under models/previous/), turns on local
     * inference in config.yml without touching anything else in it, and reloads. Runs on the config
     * executor; the future carries one operator-facing line.
     */
    public CompletableFuture<String> activateModel(String name) {
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            ioExecutor().execute(() -> {
                try {
                    result.complete(applyModel(name));
                } catch (dev.aeroac.neural.training.TrainingException refused) {
                    result.complete(refused.getMessage());
                } catch (Throwable error) {
                    result.complete("Не удалось включить модель: " + describe(error));
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.complete("Не удалось включить модель: " + describe(rejected));
        }
        return result;
    }

    private String applyModel(String name) throws Exception {
        Path models = modelsRoot(), active = activeModelPath(), file = configFile();
        AeroAPI api = AeroAPI.INSTANCE;
        ConfigManager canonical = api == null || api.getConfigManager() == null ? null : api.getConfigManager().getConfig();
        if (models == null || active == null || file == null || canonical == null) return "Папка данных Aero недоступна.";
        var info = dev.aeroac.neural.training.ModelLibrary.activate(models, name, active);
        String before = Files.readString(file);
        String after = dev.aeroac.manager.config.YamlScalars.set(before, "neural.inference.enabled", "true");
        if (after != null) after = dev.aeroac.manager.config.YamlScalars.set(after, "neural.inference.mode", "local");
        if (after == null) return "Модель скопирована в " + active + ", но " + file + " не удалось изменить: включите neural.inference.enabled и mode: local вручную.";
        if (!after.equals(before)) dev.aeroac.manager.config.NeuralFlagsFile.writeAtomically(file, after);
        boolean reloaded = api.getExternalAPI().reloadAsync(canonical).get(60, TimeUnit.SECONDS);
        NeuralRuntime current = runtime;
        String quality = Double.isNaN(info.testRocAuc()) ? "" : String.format(java.util.Locale.ROOT, " (ROC-AUC на тесте %.3f)", info.testRocAuc());
        if (!reloaded || current == null || !config.inference().enabled()) {
            return "Модель " + name + " скопирована в " + active + quality + ", но нейро-рантайм не запустился: проверьте "
                    + "neural.enabled и консоль.";
        }
        return "Модель " + name + quality + " включена: локальный режим, перезагружено. Предыдущая лежит в " + models.resolve("previous") + ".";
    }

    private Executor ioExecutor() {
        Executor current = ioExecutor;
        if (current != null) return current;
        synchronized (this) {
            if (ioExecutor == null) {
                ioExecutor = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "Aero-neural-config");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return ioExecutor;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Paths, helpers, logging
    // ---------------------------------------------------------------------------------------------

    /** Absolute path of the canonical config file, or a readable placeholder when unavailable. */
    public String configPath() {
        Path file = configFile();
        return file == null ? "неизвестно" : file.toString();
    }

    /** Absolute path of the dataset folder, or a readable placeholder when unavailable. */
    public String datasetsPath() {
        Path root = datasetsRoot();
        return root == null ? "неизвестно" : root.toAbsolutePath().toString();
    }

    private Path configFile() {
        Path folder = dataFolder();
        return folder == null ? null : folder.resolve("config.yml").toAbsolutePath();
    }

    private Path datasetsRoot() {
        Path folder = dataFolder();
        return folder == null ? null : folder.resolve("datasets");
    }

    private Path dataFolder() {
        try {
            return dataFolder.get();
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** The active data folder, or null before the platform resolver exists. */
    private static Path platformDataFolder() {
        try {
            var plugin = AeroAPI.INSTANCE.getGrimPlugin();
            if (plugin == null) return null;
            var folder = plugin.getDataFolder();
            return folder == null ? null : folder.toPath();
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** Wording in the interface locale when the catalog has it, exact Russian otherwise. */
    private static String tr(String key, String russian) {
        try {
            String resolved = AeroMessages.tr(key);
            return resolved == null || resolved.isEmpty() || resolved.equals(key) ? russian : resolved;
        } catch (Throwable unavailable) {
            return russian;
        }
    }

    /**
     * Same, for a line that has to carry values. Formatting is done with {@code Locale.ROOT} so a
     * number cannot change shape with the server's locale, and a malformed template degrades to the
     * unformatted wording instead of throwing inside a hook.
     */
    private static String tr(String key, String russianTemplate, Object... args) {
        String template = tr(key, russianTemplate);
        if (args == null || args.length == 0) return template;
        try {
            return String.format(java.util.Locale.ROOT, template, args);
        } catch (RuntimeException malformed) {
            return template;
        }
    }

    /** Short, honest description of a failure: never the string "null". */
    private static String describe(Throwable error) {
        if (error == null) return "неизвестная ошибка";
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return error.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Why a start failed, including the absolute dataset folder. An IO failure is named as such —
     * unwrapped from its {@code CompletionException} and never reduced to a blank message — because
     * "cannot open dataset: null" is indistinguishable from a disabled flag.
     */
    private String openFailure(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String kind = cause instanceof IOException ? "ошибка ввода-вывода" : "ошибка";
        return tr("cmd.aero.dataset_open_failed",
                "Не удалось начать запись (%s): %s. Папка датасетов: %s. "
                        + "Проверьте консоль и права на запись.",
                kind, describe(cause), datasetsPath());
    }

    /** Config used when no ConfigManager exists yet: every switch off, nothing invented. */
    @SuppressWarnings("unchecked")
    private static ConfigManager disabledConfig() {
        return (ConfigManager) java.lang.reflect.Proxy.newProxyInstance(
                NeuralManager.class.getClassLoader(), new Class<?>[]{ConfigManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasLoaded")) return false;
                    if (args != null && args.length == 2) return args[1];
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0d;
                    return null;
                });
    }
}
