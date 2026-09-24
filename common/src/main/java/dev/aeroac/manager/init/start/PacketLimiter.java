package dev.aeroac.manager.init.start;

import dev.aeroac.AeroAPI;
import dev.aeroac.player.AeroPlayer;

public class PacketLimiter implements StartableInitable {
    @Override
    public void start() {
        AeroAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(AeroAPI.INSTANCE.getGrimPlugin(), () -> {
            for (AeroPlayer player : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
                // Avoid concurrent reading on an integer as it's results are unknown
                player.cancelledPackets.set(0);
            }
        }, 1, 20);
    }
}
