package dev.aeroac.command.commands;

import dev.aeroac.AeroAPI;
import dev.aeroac.checks.debug.HitboxDebugHandler;
import dev.aeroac.command.BuildableCommand;
import dev.aeroac.platform.api.command.PlayerSelector;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.MessageUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AeroDebug implements BuildableCommand {

    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        Command.Builder<Sender> grimCommand = commandManager.commandBuilder("aero", "aeroac");

        // Register "debug" subcommand
        Command.Builder<Sender> debugCommand = grimCommand
                .literal("debug", Description.of("Переключить отладочный вывод для игрока"))
                .permission("grim.debug")
                .optional("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleDebug);

        // Register "consoledebug" subcommand
        Command.Builder<Sender> consoleDebugCommand = grimCommand
                .literal("consoledebug", Description.of("Переключить вывод отладки в консоль для игрока"))
                .permission("grim.consoledebug")
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleConsoleDebug);

        Command.Builder<Sender> hitboxDebugCommand = grimCommand
                .literal("hitboxdebug", Description.of("Переключить визуализацию хитбоксов"))
                .permission("grim.hitboxdebug")
                .optional("target", arguments.singlePlayerSelectorParser(), Description.of("Игрок для отладки (по умолчанию — отправитель)"))
                .handler(this::handleHitboxDebug);

        // Register command
        commandManager.command(debugCommand);
        commandManager.command(consoleDebugCommand);
        commandManager.command(hitboxDebugCommand);
    }

    private void handleDebug(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector playerSelector = context.getOrDefault("target", null);

        AeroPlayer targetGrimPlayer = parseTarget(sender, playerSelector == null ? sender : playerSelector.getSinglePlayer());
        if (targetGrimPlayer == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cИгрок в исключениях или не в сети!"));
            return;
        }

        if (sender.isConsole()) {
            targetGrimPlayer.checkManager.getDebugHandler().toggleConsoleOutput();
        } else if (sender.isPlayer()) {
            AeroPlayer senderGrimPlayer = AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId());
            if (senderGrimPlayer == null) {
                sender.sendMessage(MessageUtil.getParsedComponent(sender, "sender-not-found", "%prefix% &cБудучи в исключениях, нельзя использовать эту команду!"));
                return;
            }
            targetGrimPlayer.checkManager.getDebugHandler().toggleListener(senderGrimPlayer);
        } else {
            sender.sendMessage(MessageUtil.getParsedComponent(sender,
                    "run-as-player-or-console",
                    "%prefix% &cThis command can only be used by players or the console!")
            );
        }
    }

    private void handleConsoleDebug(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector targetName = context.getOrDefault("target", null);

        AeroPlayer grimPlayer = parseTarget(sender, targetName.getSinglePlayer());
        if (grimPlayer == null) return;

        boolean isOutput = grimPlayer.checkManager.getDebugHandler().toggleConsoleOutput();
        String playerName = grimPlayer.user.getProfile().getName(); // Use user profile for name

        Component message = Component.text()
                .append(Component.text("Вывод в консоль для ", NamedTextColor.GRAY))
                .append(Component.text(playerName, NamedTextColor.WHITE))
                .append(Component.text(" теперь ", NamedTextColor.GRAY))
                .append(Component.text(isOutput ? "enabled" : "disabled", NamedTextColor.WHITE))
                .build();

        sender.sendMessage(message);
    }

    private void handleHitboxDebug(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector playerSelector = context.getOrDefault("target", null);

        // Hitbox debug requires a *player* to be the listener (the one seeing the boxes)
        if (!sender.isPlayer()) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender,
                    "hitboxdebug-player-only",
                    "%prefix% &cHitbox debug can only be toggled by players.")
            );
            return;
        }

        // Determine the target player whose hitboxes are being debugged
        AeroPlayer targetGrimPlayer = parseTarget(sender, playerSelector == null ? sender : playerSelector.getSinglePlayer());
        if (targetGrimPlayer == null) return;

        // Get the sender's AeroPlayer data, as they are the listener
        AeroPlayer senderGrimPlayer = AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId());
        if (senderGrimPlayer == null) {
            sender.sendMessage(Component.text("Не удалось найти ваши данные игрока, чтобы зарегистрировать слушателя.", NamedTextColor.RED));
            return;
        }

        // Get the HitboxDebugHandler check instance and toggle the listener
        HitboxDebugHandler hitboxHandler = targetGrimPlayer.checkManager.getCheck(HitboxDebugHandler.class);
        if (hitboxHandler == null) {
            sender.sendMessage(Component.text("Проверка HitboxDebugHandler не найдена для указанного игрока.", NamedTextColor.RED));
            return;
        }

        boolean enabled = hitboxHandler.toggleListener(senderGrimPlayer); // Pass the sender/listener

        // Send feedback message
        Component message = Component.text()
                .append(Component.text("Отладчик хитбоксов для ", NamedTextColor.GRAY))
                .append(Component.text(targetGrimPlayer.getName(), NamedTextColor.WHITE))
                .append(Component.text(enabled ? " включён." : " выключен.", NamedTextColor.GRAY))
                .build();
        sender.sendMessage(message);
    }

    private @Nullable AeroPlayer parseTarget(@NotNull Sender sender, @Nullable Sender t) {
        if (sender.isConsole() && t == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "console-specify-target", "%prefix% &cИз консоли обязательно укажите цель!"));
            return null;
        }
        Sender target = t == null ? sender : t;

        AeroPlayer grimPlayer = AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(target.getUniqueId());
        if (grimPlayer == null) {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(sender.getPlatformPlayer().getNative());
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cИгрок в исключениях или не в сети!"));

            if (user == null) {
                sender.sendMessage(Component.text("Неизвестный пользователь PacketEvents", NamedTextColor.RED));
            } else {
                boolean isExempt = AeroAPI.INSTANCE.getPlayerDataManager().shouldCheck(user);
                if (!isExempt) {
                    sender.sendMessage(Component.text("Состояние соединения пользователя: " + user.getConnectionState(), NamedTextColor.RED));
                }
            }
        }

        return grimPlayer;
    }
}
