package ac.grim.grimac.neural.command;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.neural.NeuralMessages;
import ac.grim.grimac.neural.NeuralRuntime;
import ac.grim.grimac.neural.dataset.DatasetManager;
import ac.grim.grimac.neural.dataset.DatasetMetadata;
import ac.grim.grimac.neural.dataset.DatasetSession;
import ac.grim.grimac.neural.debug.NeuralReport;
import ac.grim.grimac.neural.inference.InferenceHealth;
import ac.grim.grimac.platform.api.command.PlayerSelector;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.StringParser;

import java.util.Locale;
import java.util.function.Consumer;

public final class NeuralCommand implements BuildableCommand {
    @Override public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        manager.command(manager.commandBuilder("neural").permission("grim.neural")
                .handler(context -> {
                    reply(context.sender(), "/neural dataset start <player> legit|unlabeled [client] [config] [notes]");
                    reply(context.sender(), "/neural dataset start <player> cheat <family> [client] [config] [notes]");
                    reply(context.sender(), "  optional flags: --scenario <name> --assist "
                            + DatasetMetadata.AssistStrength.names() + " (recording metadata, never a model input)");
                    reply(context.sender(), "/neural dataset stop <player> | /neural dataset status");
                    reply(context.sender(), "/neural profile <player> | /neural monitor <player> [off] | /neural status");
                }));
        for (DatasetMetadata.Label label : DatasetMetadata.Label.values()) {
            var command = manager.commandBuilder("neural").literal("dataset").literal("start")
                    .permission("grim.neural.dataset").required("target", arguments.singlePlayerSelectorParser())
                    .literal(label.name().toLowerCase(Locale.ROOT));
            if (label == DatasetMetadata.Label.CHEAT) command = command.required("family", StringParser.stringParser());
            command = command.optional("client", StringParser.stringParser())
                    .optional("configuration", StringParser.stringParser())
                    // Flags rather than more positionals: the recording operator types this by hand,
                    // and a fifth optional word before a greedy note is easy to get silently wrong.
                    .flag(CommandFlag.builder("scenario").withComponent(StringParser.stringParser()).build())
                    .flag(CommandFlag.builder("assist").withComponent(StringParser.stringParser()).build())
                    .optional("notes", StringParser.greedyStringParser());
            manager.command(command.handler(context -> withPlayer(context, player ->
                    GrimAPI.INSTANCE.getNeuralManager().startSession(player, label,
                            context.getOrDefault("family", ""), context.getOrDefault("client", ""),
                            context.getOrDefault("configuration", ""),
                            context.flags().<String>getValue("scenario").orElse(""),
                            context.flags().<String>getValue("assist").orElse(""),
                            context.getOrDefault("notes", ""),
                            message -> reply(context.sender(), message)))));
        }
        manager.command(manager.commandBuilder("neural").literal("dataset").literal("stop")
                .permission("grim.neural.dataset").required("target", arguments.singlePlayerSelectorParser())
                .handler(context -> withPlayer(context, player -> {
                    player.getNeuralState().stop("MANUAL_STOP");
                    reply(context.sender(), "Recording stopped; queued data is closing asynchronously. "
                            + "Telemetry itself keeps running while inference or risk is enabled.");
                })));
        manager.command(manager.commandBuilder("neural").literal("dataset").literal("status").permission("grim.neural.dataset")
                .handler(context -> {
                    DatasetManager datasets = GrimAPI.INSTANCE.getNeuralManager().datasets();
                    if (datasets == null || datasets.sessions().isEmpty()) { reply(context.sender(), "No active/closing dataset sessions."); return; }
                    for (DatasetSession session : datasets.sessions()) reply(context.sender(), status(session));
                }));
        manager.command(manager.commandBuilder("neural").literal("status").permission("grim.neural")
                .handler(context -> serviceStatus(context.sender())));
        manager.command(manager.commandBuilder("neural").literal("profile").permission("grim.neural")
                .required("target", arguments.singlePlayerSelectorParser()).handler(context -> withPlayer(context, player ->
                        NeuralReport.profile(player, player.getNeuralState(), GrimAPI.INSTANCE.getNeuralManager().runtime(),
                                System.nanoTime(), message -> reply(context.sender(), message)))));
        manager.command(manager.commandBuilder("neural").literal("monitor").permission("grim.neural.monitor")
                .required("target", arguments.singlePlayerSelectorParser())
                .optional("state", StringParser.stringParser())
                .handler(context -> {
                    boolean enable = !"off".equalsIgnoreCase(context.getOrDefault("state", "on"));
                    Sender sender = context.sender();
                    withPlayer(context, player -> reply(sender,
                            GrimAPI.INSTANCE.getNeuralManager().monitor(player, sender, enable)));
                }));
    }

    private static void serviceStatus(Sender sender) {
        NeuralRuntime runtime = GrimAPI.INSTANCE.getNeuralManager().runtime();
        if (runtime == null) {
            reply(sender, "Neural telemetry disabled. Deterministic checks are unaffected.");
            return;
        }
        reply(sender, "Telemetry generation=" + runtime.generation()
                + " collection=" + runtime.config().collectionEnabled()
                + " inference=" + runtime.config().inference().enabled()
                + " risk=" + runtime.config().risk().enabled()
                + " mitigation=" + runtime.config().mitigation().enabled());
        InferenceHealth health = runtime.health();
        reply(sender, "Inference: " + (health == null ? "disabled" : health.describe()));
        if (health != null) {
            reply(sender, "Endpoint " + runtime.config().inference().endpoint()
                    + " timeout=" + runtime.config().inference().timeoutMs() + "ms"
                    + " flash=" + runtime.config().inference().flashWindow().wireName()
                    + "/" + runtime.config().inference().flashSequence()
                    + (runtime.config().inference().proEnabled()
                    ? " pro=" + runtime.config().inference().proWindow().wireName()
                    + "/" + runtime.config().inference().proSequence() : " pro=off"));
        }
        DatasetManager datasets = GrimAPI.INSTANCE.getNeuralManager().datasets();
        if (datasets != null) {
            reply(sender, "Snapshots written=" + datasets.writtenSnapshots() + " dropped=" + datasets.droppedSnapshots());
        }
    }

    private static String status(DatasetSession session) {
        return session.metadata.sessionId() + " " + session.metadata.label() + " frames=" + session.framesWritten()
                + " queued=" + session.queued() + " dropped=" + session.dropped()
                + " state=" + (session.completed() ? "CLOSED" : session.accepting() ? "RECORDING" : "CLOSING")
                + " reason=" + session.closeReason() + (session.failure() == null ? "" : " failure=" + session.failure());
    }

    private static void withPlayer(CommandContext<Sender> context, Consumer<GrimPlayer> action) {
        PlayerSelector selector = context.get("target");
        PlatformPlayer platform = selector.getSinglePlayer().getPlatformPlayer();
        GrimPlayer player = platform == null ? null : GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(platform.getUniqueId());
        if (player == null) { reply(context.sender(), "Player is offline, exempt or on another server."); return; }
        player.runSafely(() -> action.accept(player));
    }

    private static void reply(Sender sender, String message) {
        NeuralMessages.send(sender, message);
    }
}
