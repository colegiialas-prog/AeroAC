package dev.aeroac.platform.bukkit.initables;

import dev.aeroac.manager.init.start.StartableInitable;
import dev.aeroac.platform.bukkit.AeroACBukkitLoaderPlugin;
import dev.aeroac.platform.bukkit.enforcement.NeuralDamageListener;
import dev.aeroac.platform.bukkit.events.PistonEvent;
import dev.aeroac.utils.anticheat.LogUtil;
import org.bukkit.Bukkit;

public class BukkitEventManager implements StartableInitable {
    public void start() {
        LogUtil.info("Registering singular bukkit event... (PistonEvent)");

        Bukkit.getPluginManager().registerEvents(new PistonEvent(), AeroACBukkitLoaderPlugin.LOADER);
        // Neural mitigation's damage multiplier; a no-op unless a running mitigation scales damage.
        Bukkit.getPluginManager().registerEvents(new NeuralDamageListener(), AeroACBukkitLoaderPlugin.LOADER);
    }
}
