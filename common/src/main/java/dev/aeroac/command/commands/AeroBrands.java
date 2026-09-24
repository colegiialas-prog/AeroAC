package dev.aeroac.command.commands;

import dev.aeroac.AeroAPI;
import dev.aeroac.command.BuildableCommand;
import dev.aeroac.manager.AlertManagerImpl;
import dev.aeroac.manager.datastore.PlayerToggleStore;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.api.player.PlatformPlayer;
import dev.aeroac.platform.api.sender.Sender;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class AeroBrands implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("aero", "aeroac")
                        .literal("brands", Description.of("Переключить бренды для отправителя"))
                        .permission("grim.brand")
                        .handler(this::handleBrands)
        );
    }

    private void handleBrands(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        if (sender.isPlayer()) {
            PlatformPlayer p = Objects.requireNonNull(context.sender().getPlatformPlayer());
            AlertManagerImpl am = AeroAPI.INSTANCE.getAlertManager();
            boolean newState = !am.hasBrandsEnabled(p);
            am.setBrandsEnabled(p, newState, false);
            AeroAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore()
                    .applyUserToggle(p.getUniqueId(), PlayerToggleStore.KEY_BRANDS, newState);
        } else if (sender.isConsole()) {
            AeroAPI.INSTANCE.getAlertManager().toggleConsoleBrands();
        }
    }
}
