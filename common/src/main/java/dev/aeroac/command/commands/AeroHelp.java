package dev.aeroac.command.commands;

import dev.aeroac.AeroAPI;
import dev.aeroac.command.BuildableCommand;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.utils.anticheat.MessageUtil;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.jetbrains.annotations.NotNull;

public class AeroHelp implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("aero", "aeroac")
                        .literal("help", Description.of("Показать справку"))
                        .permission("grim.help")
                        .handler(this::handleHelp)
        );
    }

    private void handleHelp(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();

        for (String string : AeroAPI.INSTANCE.getConfigManager().getConfig().getStringList("help")) {
            if (string == null) continue;
            string = MessageUtil.replacePlaceholders(sender, string);
            sender.sendMessage(MessageUtil.miniMessage(string));
        }
    }
}
