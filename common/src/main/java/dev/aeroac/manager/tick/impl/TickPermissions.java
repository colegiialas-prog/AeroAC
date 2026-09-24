package dev.aeroac.manager.tick.impl;

import dev.aeroac.AeroAPI;
import dev.aeroac.manager.config.BaseConfigManager;
import dev.aeroac.manager.tick.Tickable;
import dev.aeroac.player.AeroPlayer;

public class TickPermissions implements Tickable {

    @Override
    public void tick() {
        BaseConfigManager config = AeroAPI.INSTANCE.getConfigManager();
        int interval = config.getUpdatePermissionTicks();
        if (interval <= 0 || AeroAPI.INSTANCE.getTickManager().currentTick % interval != 0) return;

        for (AeroPlayer player : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            player.updatePermissions();
        }
    }
}
