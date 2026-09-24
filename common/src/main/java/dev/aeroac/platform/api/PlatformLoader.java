package dev.aeroac.platform.api;

import ac.grim.grimac.api.plugin.GrimPlugin;
import dev.aeroac.platform.api.command.CommandService;
import dev.aeroac.platform.api.manager.ItemResetHandler;
import dev.aeroac.platform.api.manager.MessagePlaceHolderManager;
import dev.aeroac.platform.api.manager.PermissionRegistrationManager;
import dev.aeroac.platform.api.manager.PlatformPluginManager;
import dev.aeroac.platform.api.player.PlatformPlayerFactory;
import dev.aeroac.platform.api.scheduler.PlatformScheduler;
import dev.aeroac.platform.api.sender.SenderFactory;
import com.github.retrooper.packetevents.PacketEventsAPI;
import org.jetbrains.annotations.NotNull;

public interface PlatformLoader {
    PlatformScheduler getScheduler();

    PlatformPlayerFactory getPlatformPlayerFactory();

    PacketEventsAPI<?> getPacketEvents();

    ItemResetHandler getItemResetHandler();

    CommandService getCommandService();

    SenderFactory<?> getSenderFactory();

    GrimPlugin getPlugin();

    PlatformPluginManager getPluginManager();

    PlatformServer getPlatformServer();

    // Intended for use for platform specific service/API bringup
    // Method will be called when InitManager.load() is called
    void registerAPIService();

    // Used to replace text placeholders in messages
    // Currently only supports PlaceHolderAPI on Bukkit
    @NotNull
    MessagePlaceHolderManager getMessagePlaceHolderManager();

    PermissionRegistrationManager getPermissionManager();
}
