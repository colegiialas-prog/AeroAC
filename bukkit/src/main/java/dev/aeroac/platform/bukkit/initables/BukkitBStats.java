package dev.aeroac.platform.bukkit.initables;

import dev.aeroac.manager.init.start.StartableInitable;
import dev.aeroac.platform.bukkit.AeroACBukkitLoaderPlugin;
import dev.aeroac.utils.anticheat.Constants;
import io.github.retrooper.packetevents.bstats.bukkit.Metrics;

public class BukkitBStats implements StartableInitable {
    @Override
    public void start() {
        try {
            new Metrics(AeroACBukkitLoaderPlugin.LOADER, Constants.BSTATS_PLUGIN_ID);
        } catch (Exception ignored) {}
    }
}
