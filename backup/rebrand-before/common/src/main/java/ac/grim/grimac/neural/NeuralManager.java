package ac.grim.grimac.neural;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.manager.init.stop.StoppableInitable;
import ac.grim.grimac.neural.dataset.DatasetManager;
import ac.grim.grimac.neural.dataset.DatasetMetadata;
import ac.grim.grimac.neural.inference.HttpInferenceClient;
import ac.grim.grimac.neural.inference.InferenceClient;
import ac.grim.grimac.neural.telemetry.CombatTelemetryCollector;
import ac.grim.grimac.neural.telemetry.ReachObservation;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Entry point for every hook the anticheat calls. Telemetry starts at a player's first attack and
 * stops again when combat goes quiet, so a connection that never fights costs one empty state object.
 *
 * <p>Nothing here can flag a check or punish. The only gameplay-affecting action is the optional,
 * default-off attack cancellation in Phase 6, applied after the packet has already been observed.
 */
public final class NeuralManager implements StartableInitable, StoppableInitable {
    private volatile NeuralConfig config;
    private volatile NeuralRuntime runtime;
    private volatile DatasetManager datasets;
    private long generations;
    private long lastServerTickNanos;
    private volatile double tickDurationMs = Double.NaN;
    private volatile long serverTick;

    @Override public void start() { reload(GrimAPI.INSTANCE.getConfigManager().getConfig()); }

    public synchronized void reload(ConfigManager source) {
        NeuralConfig replacement = NeuralConfig.read(source);
        NeuralRuntime previous = runtime;
        config = replacement;
        try {
            if (datasets != null) datasets.update(replacement);
            else if (needsDisk(replacement)) {
                datasets = new DatasetManager(GrimAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath().resolve("datasets"),
                        replacement, LogUtil::warn);
            }
        } catch (Exception error) {
            LogUtil.error("Aero neural disk storage unavailable; deterministic checks continue", error);
        }
        try {
            runtime = replacement.telemetryEnabled()
                    ? new NeuralRuntime(++generations, replacement, buildClient(replacement), this::datasets)
                    : null;
        } catch (Exception error) {
            runtime = null;
            LogUtil.error("Aero neural runtime disabled by a configuration error; deterministic checks continue", error);
        }
        if (previous != null) previous.close();
    }

    private static boolean needsDisk(NeuralConfig settings) {
        return settings.enabled() && (settings.collectionEnabled() || settings.risk().enabled());
    }

    private static InferenceClient buildClient(NeuralConfig settings) {
        if (!settings.inference().enabled()) return null;
        return new HttpInferenceClient(settings.inference().endpoint(), settings.inference().timeoutMs(),
                settings.inference().maxInFlight(), Math.min(4, Math.max(1, settings.inference().maxInFlight())));
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

    /** The live collector, or null when telemetry is off or this player is not in combat. */
    public CombatTelemetryCollector collector(GrimPlayer player) {
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

    /** Creates the collector on demand. Called on the first attack and when recording starts. */
    private CombatTelemetryCollector openCollector(GrimPlayer player, long nowNanos) {
        NeuralRuntime active = runtime;
        if (active == null || player.getNeuralState().disconnected) return null;
        CombatTelemetryCollector collector = collector(player);
        if (collector == null) {
            collector = new CombatTelemetryCollector(player, active.config(), active.generation(), nowNanos);
            player.getNeuralState().collector = collector;
        }
        return collector;
    }

    // Called immediately before PacketEntityReplication advances compensated interpolation.
    public void beforeEntityReplication(GrimPlayer player, PacketReceiveEvent event) {
        CombatTelemetryCollector collector = collector(player);
        if (collector == null) return;
        NeuralRuntime active = runtime;
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

    public void packet(GrimPlayer player, PacketReceiveEvent event) {
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
    private void attack(GrimPlayer player, int entityId, PacketReceiveEvent event, long now) {
        NeuralRuntime active = runtime;
        CombatTelemetryCollector collector = openCollector(player, now);
        if (collector != null) collector.attack(entityId, event.isCancelled(), now);
        if (active != null && !event.isCancelled() && active.shouldCancelAttack(player.getNeuralState(), now)) {
            event.setCancelled(true);
            if (player.getNeuralState().mitigation != null) player.getNeuralState().mitigation.suppressedOne();
        }
    }

    public void flag(GrimPlayer player, String name) {
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

    public void reach(GrimPlayer player, int id, double distance, int intersection, int los, SimpleCollisionBox box) {
        CombatTelemetryCollector collector = collector(player);
        if (collector != null) {
            try {
                collector.reach(new ReachObservation(System.nanoTime(), id, distance, intersection, los,
                        box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
            } catch (RuntimeException error) { failed(player, error); }
        }
    }

    public void discontinuity(GrimPlayer player, String reason) {
        CombatTelemetryCollector collector = collector(player);
        if (collector != null) {
            try { collector.discontinuity(reason); } catch (RuntimeException error) { failed(player, error); }
        }
    }

    private void failed(GrimPlayer player, RuntimeException error) {
        NeuralPlayerState state = player.getNeuralState();
        state.stop("TELEMETRY_ERROR");
        state.resetRuntime();
        LogUtil.error("Aero telemetry stopped for one player; deterministic checks continue", error);
    }

    /** Caller must be on player's event loop; disk open completes asynchronously back on that loop. */
    public void startSession(GrimPlayer player, DatasetMetadata.Label label, String family, String client,
                             String configuration, String scenario, String assistStrength, String notes,
                             Consumer<String> reply) {
        NeuralPlayerState state = player.getNeuralState();
        DatasetManager manager = datasets;
        NeuralConfig settings = config;
        NeuralRuntime active = runtime;
        if (manager == null || settings == null || active == null || !settings.recordingEnabled()) {
            reply.accept("Enable neural.enabled and neural.collection.enabled, then /grim reload."); return;
        }
        if (state.disconnected || state.opening || (state.session != null && !state.session.completed())) {
            reply.accept("Session already open, opening or still closing; check /neural dataset status."); return;
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
                reply.accept("Unknown assist strength " + assistStrength + "; use "
                        + DatasetMetadata.AssistStrength.names());
                return;
            }
            metadata = new DatasetMetadata(UUID.randomUUID(), manager.pseudonym(player.getUniqueId()),
                    System.currentTimeMillis(), System.nanoTime(), label, source, family, client, configuration,
                    scenario, strength, notes,
                    player.getClientVersion().getProtocolVersion(), GrimAPI.INSTANCE.getExternalAPI().getGrimVersion(),
                    settings.continuousSize(), settings.attackBefore(), settings.attackAfter());
        } catch (RuntimeException e) { reply.accept(e.getMessage()); return; }
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
                if (error != null) { reply.accept("Cannot open dataset: " + error.getMessage()); return; }
                if (state.disconnected || state.cancelOpening || runtime != active || !session.accepting()) {
                    session.close("START_CANCELLED");
                    reply.accept("Recording start cancelled by disconnect/reload/stop."); return;
                }
                state.session = session;
                CombatTelemetryCollector collector = openCollector(player, System.nanoTime());
                if (collector == null) {
                    session.close("START_CANCELLED");
                    reply.accept("Recording start cancelled; telemetry is no longer enabled."); return;
                }
                collector.attach(session);
                reply.accept("Aero AC recording " + metadata.sessionId() + " label=" + label);
            });
        });
    }

    /** Adds or removes a live watcher. Returns an operator-facing description of the outcome. */
    public String monitor(GrimPlayer player, Sender sender, boolean enable) {
        NeuralRuntime active = runtime;
        if (active == null) return "Neural telemetry is disabled; nothing to monitor.";
        NeuralPlayerState state = player.getNeuralState();
        boolean changed = enable
                ? active.monitor().add(player.getUniqueId(), sender)
                : active.monitor().remove(player.getUniqueId(), sender);
        state.monitored = active.monitor().watching(player.getUniqueId());
        if (!changed) return enable ? "Watcher limit reached for this player." : "You were not monitoring this player.";
        return (enable ? "Monitoring " : "Stopped monitoring ") + player.getName()
                + " (watchers=" + active.monitor().watcherCount(player.getUniqueId()) + ")";
    }

    public void disconnect(GrimPlayer player) {
        NeuralPlayerState state = player.getNeuralState();
        state.disconnected = true;
        state.stop("DISCONNECT");
        NeuralRuntime active = runtime;
        if (active != null) active.monitor().clear(player.getUniqueId());
        state.resetRuntime();
    }

    @Override public synchronized void stop() {
        NeuralRuntime active = runtime;
        runtime = null;
        if (active != null) active.close();
        if (datasets != null) datasets.close();
    }
}
