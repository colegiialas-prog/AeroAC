package dev.aeroac.platform.fabric.initables;

import dev.aeroac.AeroAPI;
import dev.aeroac.manager.init.start.AbstractTickEndEvent;
import dev.aeroac.platform.fabric.FabricServerEvents;
import dev.aeroac.player.AeroPlayer;

public class FabricTickEndEvent extends AbstractTickEndEvent {

    @Override
    public void start() {
        if (!super.shouldInjectEndTick()) {
            return;
        }

        FabricServerEvents.onEndTick(server -> tickAllPlayers());
    }

    private void tickAllPlayers() {
        for (AeroPlayer player : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (player.disableGrim) continue;
            super.onEndOfTick(player, true);
        }
    }
}
