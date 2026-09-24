package dev.aeroac.neural.admin.command;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.AeroAPI;
import dev.aeroac.command.BuildableCommand;
import dev.aeroac.neural.NeuralMessages;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminService;
import dev.aeroac.neural.admin.AdminSnapshot;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.admin.AdminViewMode;
import dev.aeroac.neural.admin.RecordingView;
import dev.aeroac.neural.NeuralManager;
import dev.aeroac.neural.NeuralSnapshot;
import dev.aeroac.neural.admin.training.DatasetSummary;
import dev.aeroac.neural.admin.training.EvaluationSummary;
import dev.aeroac.neural.admin.training.TrainingLaunchers;
import dev.aeroac.neural.admin.training.TrainingServiceClient;
import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.inference.InferenceHealth;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.admin.training.RecordingDraft;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.neural.admin.training.TrainingJob;
import dev.aeroac.neural.risk.RiskState;
import dev.aeroac.platform.api.command.PlayerSelector;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.api.player.PlatformPlayer;
import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.player.AeroPlayer;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * {@code /aero} — the interface an administrator is meant to live in, plus the text fallbacks.
 *
 * <p>Every subcommand that opens a screen also answers in text when no screen can be drawn: from
 * the console, and on a platform with no menu implementation.
 *
 * <p>The administrator interface answers to {@code /aero} and {@code /aeroac}. Recording and
 * diagnostics are also available as {@code /aero neural ...}; the standalone {@code /neural}
 * compatibility root remains available for existing scripts.
 *
 * <p>{@code /aero status} is the one page an operator can read without opening anything: the absolute
 * file the running configuration came from, its generation, every switch as applied, the schema
 * versions, the inference counters, the model, the dataset and the cached training state. All of it
 * is read from memory — a status command that fetched anything would put I/O on the server thread.
 */
public final class AeroCommand implements BuildableCommand {

    @Override public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        manager.command(manager.commandBuilder("aero", "aeroac").permission(permission(AdminPermissions.GUI))
                .handler(context -> open(context.sender(), gui -> gui.openMain(context.sender()),
                        () -> overview(context.sender()))));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("players").permission(permission(AdminPermissions.PLAYERS))
                .handler(context -> open(context.sender(), gui -> gui.openPlayers(context.sender(), false),
                        () -> list(context.sender(), false))));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("suspicious").permission(permission(AdminPermissions.SUSPICIOUS))
                .handler(context -> open(context.sender(), gui -> gui.openPlayers(context.sender(), true),
                        () -> list(context.sender(), true))));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("player").permission(permission(AdminPermissions.PROFILE))
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(context -> withPlatformPlayer(context, player -> open(context.sender(),
                        gui -> gui.openProfile(context.sender(), player.getUniqueId()),
                        () -> profile(context.sender(), player.getUniqueId())))));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("watch").permission(permission(AdminPermissions.MONITOR))
                .required("target", arguments.singlePlayerSelectorParser())
                .optional("state", StringParser.stringParser())
                .handler(context -> {
                    boolean enable = !"off".equalsIgnoreCase(context.getOrDefault("state", "on"));
                    Sender sender = context.sender();
                    withPlayer(context, player -> player.runSafely(() -> reply(sender,
                            AeroAPI.INSTANCE.getNeuralManager().monitor(player, sender, enable))));
                }));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("view").permission(permission(AdminPermissions.VIEW))
                .required("mode", StringParser.stringParser())
                .handler(context -> view(context.sender(), context.get("mode"))));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("alerts").permission(permission(AdminPermissions.ALERTS))
                .required("state", StringParser.stringParser())
                .handler(context -> {
                    boolean enable = !"off".equalsIgnoreCase(context.<String>get("state"));
                    service().alerts().toggle(context.sender().getUniqueId(), enable);
                    reply(context.sender(), AeroMessages.tr("cmd.aero.aero_alerts") + (enable ? AeroMessages.tr("cmd.aero.enabled") : AeroMessages.tr("cmd.aero.disabled")) + AeroMessages.tr("cmd.aero.for_you"));
                }));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("status").permission(permission(AdminPermissions.STATUS))
                .handler(context -> status(context.sender())));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("training").permission(permission(AdminPermissions.TRAINING))
                .handler(context -> open(context.sender(), gui -> gui.openTraining(context.sender()),
                        () -> training(context.sender()))));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("training").literal("status")
                .permission(permission(AdminPermissions.TRAINING))
                .handler(context -> trainingStatus(context.sender())));

        // One preset per literal, and the names come from TrainingLaunchers: a typed preset cannot
        // invent a run the training client does not know how to build.
        for (String preset : new String[]{TrainingLaunchers.PRESET_FLASH, TrainingLaunchers.PRESET_PRO}) {
            manager.command(manager.commandBuilder("aero", "aeroac").literal("training").literal("start")
                    .literal(preset).permission(permission(AdminPermissions.TRAINING_MODEL))
                    .handler(context -> startTraining(context.sender(), preset)));
        }

        manager.command(manager.commandBuilder("aero", "aeroac").literal("training").literal("active")
                .permission(permission(AdminPermissions.TRAINING))
                .handler(context -> open(context.sender(), gui -> gui.openRecordings(context.sender()),
                        () -> active(context.sender()))));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("training").literal("player")
                .permission(permission(AdminPermissions.TRAINING))
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(context -> withPlayer(context, player ->
                        recording(context.sender(), player.getUniqueId()))));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("ban").literal("list")
                .permission(permission(AdminPermissions.ENFORCE))
                .handler(context -> {
                    var lines = AeroAPI.INSTANCE.getBanService().describePending();
                    if (lines.isEmpty()) {
                        reply(context.sender(), AeroMessages.tr("ban.none_pending"));
                        return;
                    }
                    for (String line : lines) reply(context.sender(), line);
                }));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("ban").literal("confirm")
                .permission(permission(AdminPermissions.ENFORCE_CONFIRM))
                .required("id", StringParser.stringParser())
                .handler(context -> {
                    var decision = AeroAPI.INSTANCE.getBanService()
                            .confirm(context.get("id"), context.sender().getName());
                    reply(context.sender(), decision == null
                            ? AeroMessages.tr("ban.unknown_id")
                            : AeroMessages.tr("ban.confirmed", decision.name()));
                }));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("ban").literal("deny")
                .permission(permission(AdminPermissions.ENFORCE_CONFIRM))
                .required("id", StringParser.stringParser())
                .handler(context -> {
                    var decision = AeroAPI.INSTANCE.getBanService()
                            .deny(context.get("id"), context.sender().getName());
                    reply(context.sender(), decision == null
                            ? AeroMessages.tr("ban.unknown_id")
                            : AeroMessages.tr("ban.denied", decision.name()));
                }));

        // The send-off on its own, so it can be seen and tuned without banning anybody. It plays
        // the animation and stops there: no command runs, nothing is recorded as a verdict.
        manager.command(manager.commandBuilder("aero", "aeroac").literal("ban").literal("preview")
                .permission(permission(AdminPermissions.ENFORCE_CONFIRM))
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(context -> withPlayer(context, player -> {
                    Sender sender = context.sender();
                    var bans = AeroAPI.INSTANCE.getBanService();
                    var preview = new dev.aeroac.neural.enforcement.BanDecision("preview",
                            player.getUniqueId(), player.getName(), dev.aeroac.neural.risk.RiskState.CONFIRMED,
                            0, Double.NaN, null, 0, 0, System.currentTimeMillis());
                    reply(sender, AeroMessages.tr("ban.preview_started", player.getName()));
                    bans.preview(preview, () -> reply(sender, AeroMessages.tr("ban.preview_done")));
                })));

        // One command, one session. Everything the wizard asks beyond these two fields is optional
        // metadata; leaving it unset records an honest "not written down" rather than a guess, and
        // it can still be filled in afterwards. This is the path for an operator standing next to a
        // test subject who wants the recorder running now.
        manager.command(manager.commandBuilder("aero", "aeroac").literal("rec")
                .permission(permission(AdminPermissions.TRAINING_RECORD))
                .required("target", arguments.singlePlayerSelectorParser())
                .optional("family", StringParser.stringParser())
                .handler(context -> withPlayer(context, player ->
                        quickRecord(context.sender(), player, context.getOrDefault("family", "")))));

        manager.command(manager.commandBuilder("aero", "aeroac").literal("rec").literal("stop")
                .permission(permission(AdminPermissions.TRAINING_STOP))
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(context -> withPlayer(context, player -> {
                    Sender sender = context.sender();
                    player.runSafely(() -> {
                        player.getNeuralState().stop("MANUAL_STOP");
                        reply(sender, AeroMessages.tr("cmd.aero.rec.stopped", player.getName()));
                        service().invalidateDatasetSummary();
                    });
                })));

        // The wizard's free-text fields come back through here: an inventory cannot take typing, and
        // a chat listener would have to swallow messages to catch one.
        manager.command(manager.commandBuilder("aero", "aeroac").literal("training").literal("set")
                .permission(permission(AdminPermissions.TRAINING_RECORD))
                .required("field", StringParser.stringParser())
                .required("value", StringParser.greedyStringParser())
                .handler(context -> set(context.sender(), context.get("field"), context.get("value"))));
    }

    /**
     * Opens a session with the two fields that are actually required, and nothing else.
     *
     * <p>No family means honest play; a family means a cheat session at UNKNOWN assist strength,
     * which is the truthful record of a strength nobody wrote down. The same draft validation the
     * wizard uses runs here, so this cannot produce a session the long form would have refused.
     */
    private static void quickRecord(Sender sender, AeroPlayer player, String family) {
        String cheat = family == null ? "" : family.trim();
        RecordingDraft draft = new RecordingDraft().target(player.getUniqueId(), player.getName());
        draft.label(cheat.isEmpty() ? DatasetMetadata.Label.LEGIT : DatasetMetadata.Label.CHEAT);
        if (!cheat.isEmpty()) draft.cheatFamily(cheat);

        String problem = draft.problemMessage();
        if (problem != null) {
            reply(sender, problem);
            return;
        }
        player.runSafely(() -> AeroAPI.INSTANCE.getNeuralManager().startSession(player, draft.label(),
                draft.cheatFamily(), draft.clientFamily(), draft.configuration(), draft.scenario(),
                draft.assistArgument(), draft.notes(), message -> {
                    reply(sender, message);
                    service().invalidateDatasetSummary();
                }));
    }

    private static org.incendo.cloud.permission.Permission permission(String node) {
        return org.incendo.cloud.permission.Permission.anyOf(
                org.incendo.cloud.permission.Permission.of(node),
                org.incendo.cloud.permission.Permission.of(AdminPermissions.ADMIN));
    }

    private static boolean permitted(Sender sender, String node) {
        return sender.hasPermission(node) || sender.hasPermission(AdminPermissions.ADMIN);
    }

    private static AdminService service() { return AeroAPI.INSTANCE.getAdminService(); }

    /** Opens a screen when one can be drawn for this sender, and otherwise prints the fallback. */
    private static void open(Sender sender, Consumer<dev.aeroac.neural.admin.AdminGuiBridge> screen,
                             Runnable fallback) {
        var gui = service().gui();
        if (gui.available() && sender.getPlatformPlayer() != null) screen.accept(gui);
        else fallback.run();
    }

    private static void overview(Sender sender) {
        AdminSnapshot snapshot = service().snapshot();
        reply(sender, AeroMessages.tr("Aero AC: ") + snapshot.players().size()
                + AeroMessages.tr(" игроков в сети, ") + snapshot.suspicious().size()
                + AeroMessages.tr(" на наблюдении и выше"));
        for (RiskState state : RiskState.values()) {
            reply(sender, "  " + state.name() + ": " + snapshot.count(state));
        }
        reply(sender, AeroMessages.tr("cmd.aero.aero_players_aero_suspicious_aero_player_name_aero_status"));
        reply(sender, "/aero view <off|all|suspicious|auto> | /aero alerts <on|off> | /aero training");
        if (!service().gui().available()) {
            reply(sender, AeroMessages.tr("cmd.aero.no_menu_is_available_here_every_command_above_prints_its_ans"));
        }
    }

    private static void list(Sender sender, boolean suspiciousOnly) {
        AdminSnapshot snapshot = service().snapshot();
        var players = suspiciousOnly ? snapshot.suspicious() : snapshot.players();
        if (players.isEmpty()) {
            reply(sender, suspiciousOnly ? AeroMessages.tr("cmd.aero.nobody_is_flagged") : AeroMessages.tr("cmd.aero.nobody_is_online"));
            return;
        }
        int shown = 0;
        for (AdminPlayerView view : players) {
            if (shown++ >= 20) {
                reply(sender, AeroMessages.tr("cmd.aero.and") + (players.size() - 20) + AeroMessages.tr("cmd.aero.more_use_aero_player_name_for_a_specific_player"));
                break;
            }
            reply(sender, String.format(Locale.ROOT, "%-16s %-11s %s %s  risk=%s  evidence=%d",
                    view.name(), view.state(), AdminStyle.RISK_LABEL,
                    AdminStyle.percent(view.overall()), AdminStyle.number(view.risk(), 2),
                    view.evidenceCount()));
        }
    }

    private static void profile(Sender sender, java.util.UUID target) {
        AdminPlayerView view = service().snapshot().find(target);
        if (view == null) {
            reply(sender, AeroMessages.tr("cmd.aero.no_published_view_for_that_player_yet_try_again_in_a_second"));
            return;
        }
        reply(sender, view.name() + "  " + view.state() + AeroMessages.tr("cmd.aero.risk") + AdminStyle.number(view.risk(), 2)
                + AeroMessages.tr("cmd.aero.peak") + AdminStyle.number(view.peakRisk(), 2));
        reply(sender, "  " + AdminStyle.RISK_LABEL + " " + AdminStyle.percent(view.overall())
                + AeroMessages.tr("cmd.aero.aim") + AdminStyle.percent(view.aimAssist())
                + "  aura=" + AdminStyle.percent(view.killAura())
                + AeroMessages.tr("cmd.aero.trigger") + AdminStyle.percent(view.triggerBot()));
        reply(sender, AeroMessages.tr("cmd.aero.ping") + (view.ping() < 0 ? AdminStyle.NO_DATA : view.ping() + "ms")
                + AeroMessages.tr("cmd.aero.combat") + AdminStyle.duration(view.combatSeconds())
                + AeroMessages.tr("cmd.aero.evidence") + view.evidenceCount() + AeroMessages.tr("cmd.aero.predictions") + view.predictionCount());
        reply(sender, AeroMessages.tr("cmd.aero.mitigation") + (view.mitigation() == null ? AeroMessages.tr("cmd.aero.none") : view.mitigation()));
        if (view.isRecording() && permitted(sender, AdminPermissions.TRAINING)) reply(sender, AeroMessages.tr("cmd.aero.recording") + view.recording().shortLabel());
        reply(sender, AeroMessages.tr("cmd.aero.full_history_is_on_the_aero_screens_predictions_evidence_fla"));
    }

    private static void view(Sender sender, String mode) {
        PlatformPlayer platform = sender.getPlatformPlayer();
        if (platform == null) {
            reply(sender, AeroMessages.tr("cmd.aero.indicators_are_drawn_for_a_player_the_console_has_nobody_to"));
            return;
        }
        AdminViewMode parsed = AdminViewMode.parse(mode, null);
        if (parsed == null) {
            reply(sender, AeroMessages.tr("cmd.aero.use_off_all_suspicious_or_auto"));
            return;
        }
        AeroPlayer self = AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId());
        if (self == null) {
            reply(sender, AeroMessages.tr("cmd.aero.your_connection_is_not_tracked_so_no_indicator_can_be_sent_t"));
            return;
        }
        service().viewMode(self, parsed);
        reply(sender, AeroMessages.tr("cmd.aero.indicator_mode") + parsed.name() + (parsed == AdminViewMode.OFF ? ""
                : AeroMessages.tr("cmd.aero.only_you_can_see_it_nobody_s_display_name_is_changed")));
    }

    private static void status(Sender sender) {
        NeuralManager manager = AeroAPI.INSTANCE.getNeuralManager();
        NeuralSnapshot snapshot = manager.snapshot();
        reply(sender, AeroMessages.tr("Состояние Aero AC"));
        reply(sender, AeroMessages.tr("Конфигурация: ") + manager.configPath());
        reply(sender, AeroMessages.tr("Датасеты: ") + manager.datasetsPath());
        reply(sender, AeroMessages.tr("Поколение конфигурации: ") + snapshot.generation()
                + AeroMessages.tr(" | нейросеть=") + snapshot.enabled()
                + AeroMessages.tr(", запись=") + snapshot.collectionEnabled()
                + AeroMessages.tr(", inference=") + snapshot.inferenceEnabled()
                + AeroMessages.tr(", risk=") + snapshot.riskEnabled()
                + AeroMessages.tr(", mitigation=") + snapshot.mitigationEnabled()
                + AeroMessages.tr(", телеметрия=") + snapshot.telemetryEnabled()
                + AeroMessages.tr(", остановлено=") + snapshot.stopped());
        reply(sender, AeroMessages.tr("Схемы: config-version=") + configVersion()
                + AeroMessages.tr(", датасет raw=v") + CombatFrame.SCHEMA_VERSION
                + AeroMessages.tr(", признаков v") + FeatureEncoder.FEATURE_SCHEMA_VERSION);

        NeuralRuntime runtime = manager.runtime();
        InferenceHealth health = runtime == null ? null : runtime.health();
        if (runtime == null) {
            reply(sender, AeroMessages.tr("cmd.aero.neural_telemetry_is_disabled_deterministic_checks_are_unaffe"));
        } else {
            reply(sender, AeroMessages.tr("Инференс: ")
                    + (health == null ? AeroMessages.tr("cmd.aero.disabled") : health.describe()));
            reply(sender, AeroMessages.tr("В полёте: ") + (runtime.inFlight() < 0 ? AdminStyle.NO_DATA
                    : runtime.inFlight() + " / " + runtime.config().inference().maxInFlight())
                    + AeroMessages.tr(" | эндпоинт ") + runtime.config().inference().endpoint());
        }

        TrainingServiceClient training = service().training();
        EvaluationSummary current = training.currentEvaluation();
        EvaluationSummary candidate = training.candidateEvaluation();
        reply(sender, AeroMessages.tr("Модель: ") + modelVersion(current)
                + AeroMessages.tr(" | кандидат: ") + modelVersion(candidate));
        trainingLine(sender, training);

        if (!permitted(sender, AdminPermissions.TRAINING_OVERVIEW)) return;
        DatasetSummary dataset = service().datasetSummary();
        reply(sender, dataset.ready()
                ? AeroMessages.tr("cmd.aero.dataset") + dataset.sessions() + AeroMessages.tr("cmd.aero.sessions") + dataset.legit() + " LEGIT, "
                        + dataset.cheat() + " CHEAT, " + dataset.reviewed() + AeroMessages.tr("cmd.aero.human_reviewed")
                : AeroMessages.tr("cmd.aero.dataset") + (dataset.failedToLoad() ? dataset.error() : AeroMessages.tr("cmd.aero.loading_in_the_background")));
    }

    /** The cached training state: one line, plus the reason when there is no service to ask. */
    private static void trainingLine(Sender sender, TrainingServiceClient training) {
        TrainingJob job = training.job();
        reply(sender, AeroMessages.tr("Обучение: ") + job.status()
                + (job.running() ? AeroMessages.tr(" | эпоха ") + job.epoch() + "/" + job.totalEpochs()
                        + AeroMessages.tr(" | прогресс ") + AdminStyle.number(job.progress(), 2) : "")
                + AeroMessages.tr(" | сервис: ") + (training.configured()
                        ? AeroMessages.tr("настроен") : AeroMessages.tr("не настроен")));
        if (job.message() != null && !job.message().isBlank()) reply(sender, "  " + job.message());
        if (job.modelType() != null) {
            reply(sender, AeroMessages.tr("  окно=") + job.window() + AeroMessages.tr(", головы=") + job.heads()
                    + AeroMessages.tr(", признаков v") + job.featureSchemaVersion());
        }
        if (!training.configured()) {
            String reason = training.unavailableReason();
            if (reason != null && !reason.isBlank()) reply(sender, "  " + reason);
        }
        reply(sender, AeroMessages.tr("Пресеты обучения: ") + TrainingLaunchers.PRESET_FLASH + " / "
                + TrainingLaunchers.PRESET_PRO + AeroMessages.tr(" | запуск: ") + (TrainingLaunchers.available()
                        ? AeroMessages.tr("доступен") : AeroMessages.tr("недоступен"))
                + AeroMessages.tr(" | датасет: ") + (TrainingLaunchers.dataset() == null
                        ? AdminStyle.NO_DATA : TrainingLaunchers.dataset()));
    }

    /**
     * {@code /aero training status}: the cached training state, and how a run is started, from the
     * console or from a platform with no menu. Nothing here is fetched: the job state is the last
     * thing the training client heard, and the launcher is read through its seam.
     */
    private static void trainingStatus(Sender sender) {
        reply(sender, AeroMessages.tr("Состояние обучения Aero AC"));
        trainingLine(sender, service().training());
        reply(sender, AeroMessages.tr("Запись запускается так: /aero neural dataset start <игрок> <legit|cheat|unlabeled>."));
        reply(sender, AeroMessages.tr("Обучение выполняется вне сервера; JVM никогда не обучает модель."));
    }

    /** The run the plugin will not perform itself: it hands the request over and says what happened. */
    private static void startTraining(Sender sender, String preset) {
        if (!TrainingLaunchers.available()) {
            TrainingServiceClient training = service().training();
            String reason = training.unavailableReason();
            reply(sender, AeroMessages.tr("Запуск обучения недоступен")
                    + (reason == null || reason.isBlank() ? "." : ": " + reason));
            reply(sender, AeroMessages.tr("Обучение выполняется вне сервера; JVM никогда не обучает модель."));
            return;
        }
        String dataset = TrainingLaunchers.dataset();
        reply(sender, AeroMessages.tr("Запрос обучения (") + preset + AeroMessages.tr(") отправлен")
                + (dataset == null ? "." : AeroMessages.tr("; датасет: ") + dataset + "."));
        boolean handedOver = TrainingLaunchers.start(preset, message -> reply(sender, message));
        if (!handedOver) {
            reply(sender, AeroMessages.tr("Запуск обучения не принят: исполнитель стал недоступен."));
        }
    }

    /** The config schema version on disk, or a placeholder — never a made-up number. */
    private static String configVersion() {
        try {
            var configManager = AeroAPI.INSTANCE.getConfigManager();
            if (configManager == null || configManager.getConfig() == null) return AdminStyle.NO_DATA;
            int version = configManager.getConfig().getIntElse("config-version", 0);
            return version <= 0 ? AdminStyle.NO_DATA : String.valueOf(version);
        } catch (RuntimeException unreadable) {
            return AdminStyle.NO_DATA;
        }
    }

    private static String modelVersion(EvaluationSummary summary) {
        if (summary == null || !summary.present() || summary.modelVersion() == null) return AdminStyle.NO_DATA;
        return summary.modelVersion();
    }

    private static void training(Sender sender) {
        DatasetSummary dataset = service().datasetSummary();
        TrainingJob job = service().training().job();
        reply(sender, AeroMessages.tr("cmd.aero.active_recordings") + service().snapshot().recordings().size());
        if (permitted(sender, AdminPermissions.TRAINING_OVERVIEW)) {
        reply(sender, dataset.ready()
                ? AeroMessages.tr("cmd.aero.dataset") + dataset.sessions() + AeroMessages.tr("cmd.aero.sessions_legit") + dataset.legit()
                        + " | CHEAT " + dataset.cheat() + " | UNLABELED " + dataset.unlabeled()
                : AeroMessages.tr("cmd.aero.dataset_summary_is_still_loading"));
        reply(sender, AeroMessages.tr("cmd.aero.attack_windows_across_the_dataset") + (dataset.audit().attackWindows() < 0
                ? AdminStyle.NO_DATA : String.valueOf(dataset.audit().attackWindows())));
        }
        if (permitted(sender, AdminPermissions.TRAINING_MODEL)) reply(sender, AeroMessages.tr("cmd.aero.training_service") + job.status().name()
                + (job.message() == null ? "" : " - " + job.message()));
        reply(sender, AeroMessages.tr("cmd.aero.aero_training_active_aero_training_player_name"));
        reply(sender, AeroMessages.tr("cmd.aero.recording_still_starts_with_neural_dataset_start_player_or_t"));
        reply(sender, AeroMessages.tr("Запись запускается так: /aero neural dataset start <игрок> <legit|cheat|unlabeled>."));
        reply(sender, AeroMessages.tr("Состояние обучения: /aero training status | запуск: /aero training start flash|pro"));
    }

    private static void active(Sender sender) {
        var recordings = service().snapshot().recordings();
        if (recordings.isEmpty()) {
            reply(sender, AeroMessages.tr("cmd.aero.no_dataset_session_is_recording"));
            return;
        }
        for (RecordingView recording : recordings) {
            reply(sender, recording.playerName() + "  " + recording.state() + "  "
                    + recording.shortLabel() + "  " + AdminStyle.clock(recording.durationSeconds())
                    + AeroMessages.tr("cmd.aero.frames") + recording.frames() + AeroMessages.tr("cmd.aero.windows") + recording.attackWindows()
                    + AeroMessages.tr("cmd.aero.dropped") + recording.dropped()
                    + (recording.qualityWarning() ? AeroMessages.tr("cmd.aero.quality_warning") : ""));
        }
    }

    private static void recording(Sender sender, java.util.UUID target) {
        AdminPlayerView view = service().snapshot().find(target);
        RecordingView recording = view == null ? null : view.recording();
        if (recording == null) {
            reply(sender, AeroMessages.tr("cmd.aero.that_player_is_not_being_recorded"));
            return;
        }
        reply(sender, AeroMessages.tr("cmd.aero.session") + recording.sessionId() + "  " + recording.state());
        reply(sender, AeroMessages.tr("cmd.aero.label") + recording.label() + AeroMessages.tr("cmd.aero.family") + AdminStyle.blankIfEmpty(
                recording.cheatFamily(), "-") + AeroMessages.tr("cmd.aero.client") + AdminStyle.blankIfEmpty(
                recording.clientFamily(), "-") + AeroMessages.tr("cmd.aero.assist") + recording.assistStrength()
                + AeroMessages.tr("cmd.aero.scenario") + AdminStyle.blankIfEmpty(recording.scenario(), "-"));
        reply(sender, "  " + AdminStyle.clock(recording.durationSeconds()) + AeroMessages.tr("cmd.aero.frames")
                + recording.frames() + AeroMessages.tr("cmd.aero.attacks") + recording.attacks() + AeroMessages.tr("cmd.aero.swings") + recording.swings()
                + AeroMessages.tr("cmd.aero.windows") + recording.attackWindows() + AeroMessages.tr("cmd.aero.dropped") + recording.dropped());
        reply(sender, AeroMessages.tr("cmd.aero.target_known") + AdminStyle.percent(recording.targetKnown())
                + AeroMessages.tr("cmd.aero.aim_error_known") + AdminStyle.percent(recording.aimErrorKnown())
                + AeroMessages.tr("cmd.aero.geometry_known") + AdminStyle.percent(recording.geometryKnown()));
        reply(sender, AeroMessages.tr("cmd.aero.runtime_counters_only_good_review_unusable_comes_from_the_of"));
    }

    /** Fills one free-text field of this operator's recording draft and reopens the wizard. */
    private static void set(Sender sender, String field, String value) {
        RecordingDraft draft = service().draft(sender.getUniqueId());
        String cleaned = value == null ? "" : value.trim();
        switch (field.toLowerCase(Locale.ROOT)) {
            case "cheat-family", "family" -> draft.cheatFamily(cleaned);
            case "client", "client-family" -> draft.clientFamily(cleaned);
            case "configuration", "config" -> draft.configuration(cleaned);
            case "scenario" -> draft.scenario(cleaned);
            case "notes" -> draft.notes(cleaned);
            default -> {
                reply(sender, AeroMessages.tr("cmd.aero.unknown_field_use_cheat_family_client_configuration_scenario"));
                return;
            }
        }
        reply(sender, AeroMessages.tr("cmd.aero.draft") + field + " = " + cleaned);
        var gui = service().gui();
        if (gui.available() && sender.getPlatformPlayer() != null) gui.resumeRecording(sender, field);
    }

    private static void withPlatformPlayer(CommandContext<Sender> context, Consumer<PlatformPlayer> action) {
        PlayerSelector selector = context.get("target");
        PlatformPlayer player = selector.getSinglePlayer().getPlatformPlayer();
        if (player == null) reply(context.sender(), AeroMessages.tr("cmd.aero.player_is_offline_or_on_another_server"));
        else action.accept(player);
    }

    private static void withPlayer(CommandContext<Sender> context, Consumer<AeroPlayer> action) {
        PlayerSelector selector = context.get("target");
        PlatformPlayer platform = selector.getSinglePlayer().getPlatformPlayer();
        AeroPlayer player = platform == null ? null
                : AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(platform.getUniqueId());
        if (player == null) {
            reply(context.sender(), AeroMessages.tr("cmd.aero.player_is_offline_exempt_or_on_another_server"));
            return;
        }
        action.accept(player);
    }

    /** Presentation goes through the locale layer; a key with no entry resolves to this wording. */
    private static void reply(Sender sender, String message) {
        NeuralMessages.send(sender, AeroMessages.tr(message));
    }
}
