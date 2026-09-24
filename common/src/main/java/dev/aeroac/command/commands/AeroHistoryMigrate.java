package dev.aeroac.command.commands;

import dev.aeroac.AeroAPI;
import ac.grim.grimac.api.storage.backend.BackendException;
import dev.aeroac.command.BuildableCommand;
import ac.grim.grimac.internal.storage.backend.sqlite.SqliteBackend;
import ac.grim.grimac.internal.storage.checks.CheckRegistry;
import ac.grim.grimac.internal.storage.migrate.LegacyMigrator;
import ac.grim.grimac.internal.storage.migrate.V0Reader;
import dev.aeroac.manager.datastore.ClientVersionResolver;
import dev.aeroac.manager.datastore.DataStoreLifecycle;
import dev.aeroac.manager.datastore.V0Sources;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.utils.anticheat.LogUtil;
import dev.aeroac.utils.anticheat.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * {@code /aero history migrate [--delete]} — on-demand v0 → v1 migration outside
 * the startup path. Detects the legacy source by reading
 * {@code history.database.type / host / port / database / username / password}
 * from {@code config.yml} (the same keys the pre-cutover plugin wrote), builds
 * the matching JDBC URL, and runs the same {@code LegacyMigrator} startup uses.
 * <p>
 * {@code --delete} (off by default) drops the v0 {@code grim_history_*} tables
 * after state flips to {@code COMPLETE}. Operator-requested destructive action;
 * no confirmation prompt — the flag itself is the confirmation.
 * <p>
 * Runs synchronously on the command thread so RCON callers get the full output
 * before the reply channel closes. Migration against a large v0 can take
 * seconds to minutes; tolerable for a one-shot admin command.
 */
public class AeroHistoryMigrate implements BuildableCommand {

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("aero", "aeroac")
                        .literal("history")
                        .literal("migrate")
                        .permission("grim.history.migrate")
                        .flag(commandManager.flagBuilder("delete")
                                .withDescription(org.incendo.cloud.description.Description.of(
                                        "Drop the legacy v0 tables after migration completes")))
                        .handler(this::handle)
        );
    }

    private void handle(CommandContext<Sender> context) {
        Sender sender = context.sender();
        boolean delete = context.flags().hasFlag("delete");

        DataStoreLifecycle lifecycle = AeroAPI.INSTANCE.getDataStoreLifecycle();
        // isEnabled() is false when database.yml sets enabled=false;
        // isLoaded() is false when start() caught an init failure. Surface
        // each separately so the operator sees why the command won't run.
        if (!lifecycle.isEnabled()) {
            sender.sendMessage(MessageUtil.miniMessage("%prefix% &cПодсистема истории отключена!"));
            return;
        }
        if (!lifecycle.isLoaded()) {
            sender.sendMessage(MessageUtil.miniMessage("%prefix% &cНе удалось загрузить подсистему истории!"));
            return;
        }

        V0Sources.V0Source source = V0Sources.detect(
                AeroAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath(),
                AeroAPI.INSTANCE.getConfigManager().getConfig());
        if (source == null) {
            logBoth(sender, Component.text("Старый источник v0 не найден — переносить нечего.", NamedTextColor.YELLOW));
            return;
        }

        logBoth(sender, Component.text()
                .append(Component.text("Начат перенос v0 → v1 из ", NamedTextColor.AQUA))
                .append(Component.text(source.summary(), NamedTextColor.WHITE))
                .build());

        try {
            LegacyMigrator.Result result =
                    runLegacy(lifecycle, source, sender);
            logBoth(sender, Component.text()
                    .append(Component.text("Перенос завершён: ", NamedTextColor.GREEN))
                    .append(Component.text(result.sessionsWritten() + " сессий, "))
                    .append(Component.text(result.violationsWritten() + " нарушений за "))
                    .append(Component.text(result.elapsedMs() + "ms"))
                    .append(result.resumed() ? Component.text(" (продолжено)", NamedTextColor.GRAY) : Component.empty())
                    .build());
            if (delete) {
                dropLegacy(source, sender);
            }
        } catch (BackendException e) {
            logBoth(sender, Component.text("Перенос не удался: " + e.getMessage(), NamedTextColor.RED));
            LogUtil.error("Перенос старой истории через /aero history migrate не удался", e);
        }
    }

    private LegacyMigrator.Result runLegacy(
            DataStoreLifecycle lifecycle, V0Sources.V0Source source, Sender sender) throws BackendException {
        V0Reader reader =
                new V0Reader(
                        source.jdbcUrl(), source.username(), source.password());
        // Legacy migration only targets SQLite today — V0Reader understands
        // the old grim_history_* schema and writes through SqliteBackend's
        // bulk-import path. /aero history copy is the general-purpose
        // cross-backend hammer once more targets exist.
        SqliteBackend v1 = lifecycle.sqliteBackendForCommands();
        if (v1 == null) {
            throw new BackendException(
                    "no SQLite backend in routing — legacy migration needs SQLite as its target; "
                            + "switch a category to sqlite in database.yml or use /aero history copy instead");
        }
        CheckRegistry registry = lifecycle.checkRegistryForCommands();
        long gapMs = lifecycle.config().session().gapMs();
        LegacyMigrator migrator =
                new LegacyMigrator(
                        reader, v1, registry,
                        ClientVersionResolver::legacyStringToPvn,
                        gapMs, Logger.getLogger("grim-history-migrate"));
        return migrator.run(count -> {
            if (count > 0 && count % 5000 == 0) {
                logBoth(sender, Component.text("… " + count + " violations migrated so far", NamedTextColor.GRAY));
            }
        });
    }

    private void dropLegacy(V0Sources.V0Source source, Sender sender) {
        logBoth(sender, Component.text("--delete: удаление старых таблиц v0…", NamedTextColor.YELLOW));
        String[] tables = {
                "grim_history_violations",
                "grim_history_check_names",
                "grim_history_servers",
                "grim_history_versions",
                "grim_history_client_brands",
                "grim_history_client_versions",
                "grim_history_server_versions",
        };
        try (Connection c = open(source); Statement s = c.createStatement()) {
            for (String t : tables) {
                try { s.executeUpdate("DROP TABLE IF EXISTS " + t); }
                catch (SQLException e) {
                    logBoth(sender, Component.text("  удаление " + t + " не удалось: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            logBoth(sender, Component.text("Старые таблицы v0 удалены.", NamedTextColor.GREEN));
        } catch (SQLException e) {
            logBoth(sender, Component.text("Не удалось открыть старый источник для --delete: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private static Connection open(V0Sources.V0Source source) throws SQLException {
        if (source.username() == null && source.password() == null) {
            return DriverManager.getConnection(source.jdbcUrl());
        }
        return DriverManager.getConnection(source.jdbcUrl(), source.username(), source.password());
    }

    private static void logBoth(Sender sender, Component msg) {
        sender.sendMessage(msg);
        // Console log in plain text so operators still see progress if the
        // command channel (RCON connection) closes mid-run.
        LogUtil.info(plain(msg));
    }

    private static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        flatten(c, sb);
        return sb.toString();
    }

    private static void flatten(Component c, StringBuilder sb) {
        if (c instanceof net.kyori.adventure.text.TextComponent tc) sb.append(tc.content());
        for (Component child : c.children()) flatten(child, sb);
    }
}
