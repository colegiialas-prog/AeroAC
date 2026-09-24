package dev.aeroac.manager.tick.impl;

import dev.aeroac.AeroAPI;
import dev.aeroac.manager.tick.Tickable;
import dev.aeroac.player.AeroPlayer;

public class TickInventory implements Tickable {
    @Override
    public void tick() {
        for (AeroPlayer player : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            player.inventory.inventory.getInventoryStorage().tickWithBukkit();
        }
    }
}
