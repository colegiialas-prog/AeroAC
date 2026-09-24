package dev.aeroac.manager.init.start;

import dev.aeroac.AeroAPI;
import dev.aeroac.platform.api.Platform;
import dev.aeroac.utils.anticheat.LogUtil;

public class TickRunner implements StartableInitable {
    @Override
    public void start() {
        LogUtil.info("Registering tick schedulers...");

        if (AeroAPI.INSTANCE.getPlatform() == Platform.FOLIA) {
            AeroAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(AeroAPI.INSTANCE.getGrimPlugin(), () -> {
                AeroAPI.INSTANCE.getTickManager().tickSync();
                AeroAPI.INSTANCE.getTickManager().tickAsync();
            }, 1, 1);
        } else {
            AeroAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().runAtFixedRate(AeroAPI.INSTANCE.getGrimPlugin(), () -> AeroAPI.INSTANCE.getTickManager().tickSync(), 0, 1);
            AeroAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(AeroAPI.INSTANCE.getGrimPlugin(), () -> AeroAPI.INSTANCE.getTickManager().tickAsync(), 0, 1);
        }
    }
}
