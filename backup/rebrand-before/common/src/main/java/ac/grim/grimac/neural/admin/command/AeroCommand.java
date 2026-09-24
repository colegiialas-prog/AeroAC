package ac.grim.grimac.neural.admin.command;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.neural.NeuralMessages;
import ac.grim.grimac.neural.NeuralRuntime;
import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminPlayerView;
import ac.grim.grimac.neural.admin.AdminService;
import ac.grim.grimac.neural.admin.AdminSnapshot;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.admin.AdminViewMode;
import ac.grim.grimac.neural.admin.RecordingView;
import ac.grim.grimac.neural.admin.training.DatasetSummary;
import ac.grim.grimac.neural.admin.training.RecordingDraft;
import ac.grim.grimac.neural.admin.training.TrainingJob;
import ac.grim.grimac.neural.risk.RiskState;
import ac.grim.grimac.platform.api.command.PlayerSelector;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * {@code /aero} — the interface an administrator is meant to live in, plus the text fallbacks.
 *
 * <p>Every subcommand that opens a screen also answers in text when no screen can be drawn: from
 * the console, and on a platform with no menu implementation. The existing {@code /neural ...}
 * commands are untouched and stay the debug surface; nothing here replaces them, and the recording
 * commands in particular remain the documented path for scripting.
 */
public final class AeroCommand implements BuildableCommand {

    @Override public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        manager.command(manager.commandBuilder("aero").permission(permission(AdminPermissions.GUI))
                .handler(context -> open(context.sender(), gui -> gui.openMain(context.sender()),
                        () -> overview(context.sender()))));

        manager.command(manager.commandBuilder("aero").literal("players").permission(permission(AdminPermissions.PLAYERS))
                .handler(context -> open(context.sender(), gui -> gui.openPlayers(context.sender(), false),
                        () -> list(context.sender(), false))));

        manager.command(manager.commandBuilder("aero").literal("suspicious").permission(permission(AdminPermissions.SUSPICIOUS))
                .handler(context -> open(context.sender(), gui -> gui.openPlayers(context.sender(), true),
                        () -> list(context.sender(), true))));

        manager.command(manager.commandBuilder("aero").literal("player").permission(permission(AdminPermissions.PROFILE))
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(context -> withPlatformPlayer(context, player -> open(context.sender(),
                        gui -> gui.openProfile(context.sender(), player.getUniqueId()),
                        () -> profile(context.sender(), player.getUniqueId())))));

        manager.command(manager.commandBuilder("aero").literal("watch").permission(permission(AdminPermissions.MONITOR))
                .required("target", arguments.singlePlayerSelectorParser())
                .optional("state", StringParser.stringParser())
                .handler(context -> {
                    boolean enable = !"off".equalsIgnoreCase(context.getOrDefault("state", "on"));
                    Sender sender = context.sender();
                    withPlayer(context, player -> player.runSafely(() -> reply(sender,
                            GrimAPI.INSTANCE.getNeuralManager().monitor(player, sender, enable))));
                }));

        manager.command(manager.commandBuilder("aero").literal("view").permission(permission(AdminPermissions.VIEW))
                .required("mode", StringParser.stringParser())
                .handler(context -> view(context.sender(), context.get("mode"))));

        manager.command(manager.commandBuilder("aero").literal("alerts").permission(permission(AdminPermissions.ALERTS))
                .required("state", StringParser.stringParser())
                .handler(context -> {
                    boolean enable = !"off".equalsIgnoreCase(context.<String>get("state"));
                    service().alerts().toggle(context.sender().getUniqueId(), enable);
                    reply(context.sender(), "Aero alerts " + (enable ? "enabled" : "disabled") + " for you.");
                }));

        manager.command(manager.commandBuilder("aero").literal("status").permission(permission(AdminPermissions.STATUS))
                .handler(context -> status(context.sender())));

        manager.command(manager.commandBuilder("aero").literal("training").permission(permission(AdminPermissions.TRAINING))
                .handler(context -> open(context.sender(), gui -> gui.openTraining(context.sender()),
                        () -> training(context.sender()))));

        manager.command(manager.commandBuilder("aero").literal("training").literal("active")
                .permission(permission(AdminPermissions.TRAINING))
                .handler(context -> open(context.sender(), gui -> gui.openRecordings(context.sender()),
                        () -> active(context.sender()))));

        manager.command(manager.commandBuilder("aero").literal("training").literal("player")
                .permission(permission(AdminPermissions.TRAINING))
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(context -> withPlayer(context, player ->
                        recording(context.sender(), player.getUniqueId()))));

        // The wizard's free-text fields come back through here: an inventory cannot take typing, and
        // a chat listener would have to swallow messages to catch one.
        manager.command(manager.commandBuilder("aero").literal("training").literal("set")
                .permission(permission(AdminPermissions.TRAINING_RECORD))
                .required("field", StringParser.stringParser())
                .required("value", StringParser.greedyStringParser())
                .handler(context -> set(context.sender(), context.get("field"), context.get("value"))));
    }

    private static org.incendo.cloud.permission.Permission permission(String node) {
        return org.incendo.cloud.permission.Permission.anyOf(
                org.incendo.cloud.permission.Permission.of(node),
                org.incendo.cloud.permission.Permission.of(AdminPermissions.ADMIN));
    }

    private static boolean permitted(Sender sender, String node) {
        return sender.hasPermission(node) || sender.hasPermission(AdminPermissions.ADMIN);
    }

    private static AdminService service() { return GrimAPI.INSTANCE.getAdminService(); }

    /** Opens a screen when one can be drawn for this sender, and otherwise prints the fallback. */
    private static void open(Sender sender, Consumer<ac.grim.grimac.neural.admin.AdminGuiBridge> screen,
                             Runnable fallback) {
        var gui = service().gui();
        if (gui.available() && sender.getPlatformPlayer() != null) screen.accept(gui);
        else fallback.run();
    }

    private static void overview(Sender sender) {
        AdminSnapshot snapshot = service().snapshot();
        reply(sender, "Aero: " + snapshot.players().size() + " online, "
                + snapshot.suspicious().size() + " at or above WATCH.");
        for (RiskState state : RiskState.values()) {
            reply(sender, "  " + state.name() + ": " + snapshot.count(state));
        }
        reply(sender, "/aero players | /aero suspicious | /aero player <name> | /aero status");
        reply(sender, "/aero view <off|all|suspicious|auto> | /aero alerts <on|off> | /aero training");
        if (!service().gui().available()) {
            reply(sender, "No menu is available here; every command above prints its answer instead.");
        }
    }

    private static void list(Sender sender, boolean suspiciousOnly) {
        AdminSnapshot snapshot = service().snapshot();
        var players = suspiciousOnly ? snapshot.suspicious() : snapshot.players();
        if (players.isEmpty()) {
            reply(sender, suspiciousOnly ? "Nobody is flagged." : "Nobody is online.");
            return;
        }
        int shown = 0;
        for (AdminPlayerView view : players) {
            if (shown++ >= 20) {
                reply(sender, "... and " + (players.size() - 20) + " more; use /aero player <name> for a specific player.");
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
            reply(sender, "No published view for that player yet; try again in a second.");
            return;
        }
        reply(sender, view.name() + "  " + view.state() + "  risk=" + AdminStyle.number(view.risk(), 2)
                + "  peak=" + AdminStyle.number(view.peakRisk(), 2));
        reply(sender, "  " + AdminStyle.RISK_LABEL + " " + AdminStyle.percent(view.overall())
                + "  aim=" + AdminStyle.percent(view.aimAssist())
                + "  aura=" + AdminStyle.percent(view.killAura())
                + "  trigger=" + AdminStyle.percent(view.triggerBot()));
        reply(sender, "  ping=" + (view.ping() < 0 ? AdminStyle.NO_DATA : view.ping() + "ms")
                + "  combat=" + AdminStyle.duration(view.combatSeconds())
                + "  evidence=" + view.evidenceCount() + "  predictions=" + view.predictionCount());
        reply(sender, "  mitigation=" + (view.mitigation() == null ? "none" : view.mitigation()));
        if (view.isRecording() && permitted(sender, AdminPermissions.TRAINING)) reply(sender, "  recording " + view.recording().shortLabel());
        reply(sender, "Full history is on the /aero screens: predictions, evidence, flags, mitigation.");
    }

    private static void view(Sender sender, String mode) {
        PlatformPlayer platform = sender.getPlatformPlayer();
        if (platform == null) {
            reply(sender, "Indicators are drawn for a player; the console has nobody to draw them for.");
            return;
        }
        AdminViewMode parsed = AdminViewMode.parse(mode, null);
        if (parsed == null) {
            reply(sender, "Use off, all, suspicious or auto.");
            return;
        }
        GrimPlayer self = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId());
        if (self == null) {
            reply(sender, "Your connection is not tracked, so no indicator can be sent to you.");
            return;
        }
        service().viewMode(self, parsed);
        reply(sender, "Indicator mode: " + parsed.name() + (parsed == AdminViewMode.OFF ? ""
                : ". Only you can see it; nobody's display name is changed."));
    }

    private static void status(Sender sender) {
        NeuralRuntime runtime = GrimAPI.INSTANCE.getNeuralManager().runtime();
        if (runtime == null) {
            reply(sender, "Neural telemetry is disabled. Deterministic checks are unaffected.");
            return;
        }
        var health = runtime.health();
        reply(sender, "Telemetry generation " + runtime.generation()
                + " | inference=" + runtime.config().inference().enabled()
                + " risk=" + runtime.config().risk().enabled()
                + " mitigation=" + runtime.config().mitigation().enabled());
        reply(sender, "Inference: " + (health == null ? "disabled" : health.describe()));
        reply(sender, "In flight: " + (runtime.inFlight() < 0 ? AdminStyle.NO_DATA
                : runtime.inFlight() + " / " + runtime.config().inference().maxInFlight()));
        if (!permitted(sender, AdminPermissions.TRAINING_OVERVIEW)) return;
        DatasetSummary dataset = service().datasetSummary();
        reply(sender, dataset.ready()
                ? "Dataset: " + dataset.sessions() + " sessions, " + dataset.legit() + " LEGIT, "
                        + dataset.cheat() + " CHEAT, " + dataset.reviewed() + " human reviewed."
                : "Dataset: " + (dataset.failedToLoad() ? dataset.error() : "loading in the background."));
    }

    private static void training(Sender sender) {
        DatasetSummary dataset = service().datasetSummary();
        TrainingJob job = service().training().job();
        reply(sender, "Active recordings: " + service().snapshot().recordings().size());
        if (permitted(sender, AdminPermissions.TRAINING_OVERVIEW)) {
        reply(sender, dataset.ready()
                ? "Dataset: " + dataset.sessions() + " sessions | LEGIT " + dataset.legit()
                        + " | CHEAT " + dataset.cheat() + " | UNLABELED " + dataset.unlabeled()
                : "Dataset summary is still loading.");
        reply(sender, "Attack windows across the dataset: " + (dataset.audit().attackWindows() < 0
                ? AdminStyle.NO_DATA : String.valueOf(dataset.audit().attackWindows())));
        }
        if (permitted(sender, AdminPermissions.TRAINING_MODEL)) reply(sender, "Training service: " + job.status().name()
                + (job.message() == null ? "" : " - " + job.message()));
        reply(sender, "/aero training active | /aero training player <name>");
        reply(sender, "Recording still starts with /neural dataset start <player> ... or the /aero wizard.");
    }

    private static void active(Sender sender) {
        var recordings = service().snapshot().recordings();
        if (recordings.isEmpty()) {
            reply(sender, "No dataset session is recording.");
            return;
        }
        for (RecordingView recording : recordings) {
            reply(sender, recording.playerName() + "  " + recording.state() + "  "
                    + recording.shortLabel() + "  " + AdminStyle.clock(recording.durationSeconds())
                    + "  frames=" + recording.frames() + "  windows=" + recording.attackWindows()
                    + "  dropped=" + recording.dropped()
                    + (recording.qualityWarning() ? "  QUALITY WARNING" : ""));
        }
    }

    private static void recording(Sender sender, java.util.UUID target) {
        AdminPlayerView view = service().snapshot().find(target);
        RecordingView recording = view == null ? null : view.recording();
        if (recording == null) {
            reply(sender, "That player is not being recorded.");
            return;
        }
        reply(sender, "Session " + recording.sessionId() + "  " + recording.state());
        reply(sender, "  label=" + recording.label() + " family=" + AdminStyle.blankIfEmpty(
                recording.cheatFamily(), "-") + " client=" + AdminStyle.blankIfEmpty(
                recording.clientFamily(), "-") + " assist=" + recording.assistStrength()
                + " scenario=" + AdminStyle.blankIfEmpty(recording.scenario(), "-"));
        reply(sender, "  " + AdminStyle.clock(recording.durationSeconds()) + "  frames="
                + recording.frames() + " attacks=" + recording.attacks() + " swings=" + recording.swings()
                + " windows=" + recording.attackWindows() + " dropped=" + recording.dropped());
        reply(sender, "  target known " + AdminStyle.percent(recording.targetKnown())
                + ", aim error known " + AdminStyle.percent(recording.aimErrorKnown())
                + ", geometry known " + AdminStyle.percent(recording.geometryKnown()));
        reply(sender, "  Runtime counters only. GOOD/REVIEW/UNUSABLE comes from the offline audit.");
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
                reply(sender, "Unknown field. Use cheat-family, client, configuration, scenario or notes.");
                return;
            }
        }
        reply(sender, "Draft " + field + " = " + cleaned);
        var gui = service().gui();
        if (gui.available() && sender.getPlatformPlayer() != null) gui.resumeRecording(sender, field);
    }

    private static void withPlatformPlayer(CommandContext<Sender> context, Consumer<PlatformPlayer> action) {
        PlayerSelector selector = context.get("target");
        PlatformPlayer player = selector.getSinglePlayer().getPlatformPlayer();
        if (player == null) reply(context.sender(), "Player is offline or on another server.");
        else action.accept(player);
    }

    private static void withPlayer(CommandContext<Sender> context, Consumer<GrimPlayer> action) {
        PlayerSelector selector = context.get("target");
        PlatformPlayer platform = selector.getSinglePlayer().getPlatformPlayer();
        GrimPlayer player = platform == null ? null
                : GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(platform.getUniqueId());
        if (player == null) {
            reply(context.sender(), "Player is offline, exempt or on another server.");
            return;
        }
        action.accept(player);
    }

    private static void reply(Sender sender, String message) {
        NeuralMessages.send(sender, message);
    }
}
