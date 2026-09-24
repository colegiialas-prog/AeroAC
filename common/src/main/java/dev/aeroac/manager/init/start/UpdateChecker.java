package dev.aeroac.manager.init.start;

import dev.aeroac.AeroAPI;
import dev.aeroac.command.commands.AeroVersion;

public class UpdateChecker implements StartableInitable {
    @Override
    public void start() {
        if (AeroAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("check-for-updates", true)) {
            AeroVersion.checkForUpdatesAsync(AeroAPI.INSTANCE.getPlatformServer().getConsoleSender());
        }
    }
}
