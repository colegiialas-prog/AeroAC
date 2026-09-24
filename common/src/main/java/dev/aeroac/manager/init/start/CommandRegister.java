package dev.aeroac.manager.init.start;

import dev.aeroac.platform.api.command.CommandService;
import dev.aeroac.utils.anticheat.LogUtil;

public record CommandRegister(CommandService service) implements StartableInitable {

    @Override
    public void start() {
        try {
            if (service != null) {
                service.registerCommands();
            }
        } catch (Throwable t) {
            // This is the ultimate safety net. If command registration fails, Aero AC keeps running.
            LogUtil.error("Failed to register commands! Aero AC will run without command support.", t);
        }
    }
}
