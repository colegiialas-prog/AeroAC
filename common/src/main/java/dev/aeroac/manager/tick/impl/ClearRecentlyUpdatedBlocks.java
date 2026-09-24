package dev.aeroac.manager.tick.impl;

import dev.aeroac.AeroAPI;
import dev.aeroac.manager.tick.Tickable;
import dev.aeroac.player.AeroPlayer;

public class ClearRecentlyUpdatedBlocks implements Tickable {

    private static final int maxTickAge = 2;

    @Override
    public void tick() {
        for (AeroPlayer player : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            player.blockHistory.cleanup(AeroAPI.INSTANCE.getTickManager().currentTick - maxTickAge);
        }
    }
}
