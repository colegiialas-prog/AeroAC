package dev.aeroac.neural.command;

import dev.aeroac.AeroAPI;
import dev.aeroac.command.BuildableCommand;
import dev.aeroac.locale.AeroMessages;
import dev.aeroac.neural.NeuralManager;
import dev.aeroac.neural.NeuralMessages;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.NeuralSnapshot;
import dev.aeroac.neural.dataset.DatasetManager;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.neural.dataset.DatasetSession;
import dev.aeroac.neural.debug.NeuralReport;
import dev.aeroac.neural.inference.InferenceHealth;
import dev.aeroac.platform.api.command.PlayerSelector;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.api.player.PlatformPlayer;
import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.player.AeroPlayer;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.StringParser;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * The recording and diagnostics surface, reachable as {@code /neural ...},
 * {@code /aero neural ...} and {@code /aeroac neural ...}. The standalone root is retained for
 * scripts and installations that used it before the administrator interface was added.
 *
 * <p>Every line an operator reads is Russian, routed through {@link AeroMessages#tr(String)} so the
 * wording stays the interface layer's decision. Values — identifiers, counts, paths, enum constants
 * such as {@code LEGIT}/{@code CHEAT}/{@code CLOSED} — are never translation: the model, the offline
 * audit and the stored dataset read those back.
 */
public final class NeuralCommand implements BuildableCommand {

    @Override public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        registerTree(manager, arguments, true);
        registerTree(manager, arguments, false);
    }

    private static void registerTree(CommandManager<Sender> manager,
                                     CloudPlatformCommandArguments arguments, boolean underAero) {
        String shown = underAero ? "/aero neural" : "/neural";
        manager.command(root(manager, underAero).permission("grim.neural")
                .handler(context -> {
                    reply(context.sender(), shown + " dataset start <игрок> legit|unlabeled [клиент] [конфигурация] [заметки]");
                    reply(context.sender(), shown + " dataset start <игрок> cheat <семейство> [клиент] [конфигурация] [заметки]");
                    reply(context.sender(), "  необязательные флаги: --scenario <имя> --assist "
                            + DatasetMetadata.AssistStrength.names() + " (метаданные записи, никогда не вход модели)");
                    reply(context.sender(), shown + " dataset stop <игрок> | " + shown + " dataset status");
                    reply(context.sender(), shown + " profile <игрок> | " + shown + " monitor <игрок> [off] | " + shown + " status");
                }));
        for (DatasetMetadata.Label label : DatasetMetadata.Label.values()) {
            var command = root(manager, underAero).literal("dataset").literal("start")
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
                    AeroAPI.INSTANCE.getNeuralManager().startSession(player, label,
                            context.getOrDefault("family", ""), context.getOrDefault("client", ""),
                            context.getOrDefault("configuration", ""),
                            context.flags().<String>getValue("scenario").orElse(""),
                            context.flags().<String>getValue("assist").orElse(""),
                            context.getOrDefault("notes", ""),
                            message -> reply(context.sender(), message)))));
        }
        manager.command(root(manager, underAero).literal("dataset").literal("stop")
                .permission("grim.neural.dataset").required("target", arguments.singlePlayerSelectorParser())
                .handler(context -> withPlayer(context, player -> {
                    player.getNeuralState().stop("MANUAL_STOP");
                    reply(context.sender(), "Запись остановлена; очередь дописывается асинхронно. "
                            + "Телеметрия продолжает работать, пока включены inference или risk.");
                })));
        manager.command(root(manager, underAero).literal("dataset").literal("status").permission("grim.neural.dataset")
                .handler(context -> {
                    DatasetManager datasets = AeroAPI.INSTANCE.getNeuralManager().datasets();
                    if (datasets == null || datasets.sessions().isEmpty()) {
                        reply(context.sender(), "Активных или закрывающихся сессий датасета нет.");
                        return;
                    }
                    for (DatasetSession session : datasets.sessions()) reply(context.sender(), status(session));
                }));
        manager.command(root(manager, underAero).literal("status").permission("grim.neural")
                .handler(context -> serviceStatus(context.sender())));
        manager.command(root(manager, underAero).literal("profile").permission("grim.neural")
                .required("target", arguments.singlePlayerSelectorParser()).handler(context -> withPlayer(context, player ->
                        NeuralReport.profile(player, player.getNeuralState(), AeroAPI.INSTANCE.getNeuralManager().runtime(),
                                System.nanoTime(), message -> reply(context.sender(), message)))));
        manager.command(root(manager, underAero).literal("monitor").permission("grim.neural.monitor")
                .required("target", arguments.singlePlayerSelectorParser())
                .optional("state", StringParser.stringParser())
                .handler(context -> {
                    boolean enable = !"off".equalsIgnoreCase(context.getOrDefault("state", "on"));
                    Sender sender = context.sender();
                    withPlayer(context, player -> reply(sender,
                            AeroAPI.INSTANCE.getNeuralManager().monitor(player, sender, enable)));
                }));
    }

    private static Command.Builder<Sender> root(CommandManager<Sender> manager, boolean underAero) {
        return underAero
                ? manager.commandBuilder("aero", "aeroac").literal("neural")
                : manager.commandBuilder("neural");
    }

    /**
     * One page of honest state: which file the configuration came from, which generation is running,
     * every switch as it was actually applied, then the inference counters and what the dataset
     * storage has done. A disabled module prints the paths and the flags too — that is exactly the
     * case where an operator needs to know whether the file the plugin reads is the file they edited.
     */
    private static void serviceStatus(Sender sender) {
        NeuralManager manager = AeroAPI.INSTANCE.getNeuralManager();
        NeuralSnapshot snapshot = manager.snapshot();
        reply(sender, "Конфигурация: " + manager.configPath());
        reply(sender, "Датасеты: " + manager.datasetsPath());
        reply(sender, "Поколение=" + snapshot.generation()
                + " neural.enabled=" + snapshot.enabled()
                + " collection=" + snapshot.collectionEnabled()
                + " inference=" + snapshot.inferenceEnabled()
                + " risk=" + snapshot.riskEnabled()
                + " mitigation=" + snapshot.mitigationEnabled()
                + " runtime=" + snapshot.telemetryEnabled()
                + " stopped=" + snapshot.stopped());
        NeuralRuntime runtime = manager.runtime();
        if (runtime == null) {
            reply(sender, "Нейро-телеметрия отключена. Детерминированные проверки не затронуты.");
            return;
        }
        InferenceHealth health = runtime.health();
        reply(sender, "Инференс: " + (health == null
                ? "выключен"
                : health.describe()));
        if (health != null) {
            reply(sender, (runtime.config().inference().local()
                    ? "Локально " + runtime.config().inference().flashBundle()
                    : "Эндпоинт " + runtime.config().inference().endpoint())
                    + " timeout=" + runtime.config().inference().timeoutMs() + "ms"
                    + " в полёте=" + runtime.inFlight() + "/" + runtime.config().inference().maxInFlight()
                    + " flash=" + runtime.config().inference().flashWindow().wireName()
                    + "/" + runtime.config().inference().flashSequence()
                    + (runtime.config().inference().proEnabled()
                    ? " pro=" + runtime.config().inference().proWindow().wireName()
                    + "/" + runtime.config().inference().proSequence() : " pro=off"));
        }
        DatasetManager datasets = manager.datasets();
        if (datasets != null) {
            reply(sender, "Снимков записано=" + datasets.writtenSnapshots()
                    + " отброшено=" + datasets.droppedSnapshots());
        }
    }

    /** Internal field names stay as they are: the dataset audit reads them back. */
    private static String status(DatasetSession session) {
        return session.metadata.sessionId() + " " + session.metadata.label()
                + " кадров=" + session.framesWritten()
                + " в очереди=" + session.queued() + " отброшено=" + session.dropped()
                + " состояние=" + (session.completed() ? "CLOSED" : session.accepting() ? "RECORDING" : "CLOSING")
                + " reason=" + session.closeReason()
                + (session.failure() == null ? "" : " failure=" + session.failure());
    }

    private static void withPlayer(CommandContext<Sender> context, Consumer<AeroPlayer> action) {
        PlayerSelector selector = context.get("target");
        PlatformPlayer platform = selector.getSinglePlayer().getPlatformPlayer();
        AeroPlayer player = platform == null ? null : AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(platform.getUniqueId());
        if (player == null) {
            reply(context.sender(), "Игрок не в сети, в исключениях или на другом сервере.");
            return;
        }
        player.runSafely(() -> action.accept(player));
    }

    /** Presentation goes through the locale layer; a key with no entry resolves to this text. */
    private static void reply(Sender sender, String message) {
        NeuralMessages.send(sender, AeroMessages.tr(message));
    }
}
