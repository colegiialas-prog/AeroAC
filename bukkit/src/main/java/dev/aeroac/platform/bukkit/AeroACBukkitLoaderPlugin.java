package dev.aeroac.platform.bukkit;

import dev.aeroac.AeroAPI;
import dev.aeroac.AeroExternalAPI;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.plugin.GrimPlugin;
import dev.aeroac.command.CloudCommandService;
import ac.grim.grimac.internal.platform.bukkit.resolver.BukkitResolverRegistrar;
import dev.aeroac.manager.config.LegacyDataFolderMigration;
import dev.aeroac.manager.init.Initable;
import dev.aeroac.manager.init.start.ExemptOnlinePlayersOnReload;
import dev.aeroac.manager.init.start.StartableInitable;
import dev.aeroac.platform.api.Platform;
import dev.aeroac.platform.api.PlatformLoader;
import dev.aeroac.platform.api.PlatformServer;
import dev.aeroac.platform.api.command.CommandService;
import dev.aeroac.platform.api.manager.ItemResetHandler;
import dev.aeroac.platform.api.manager.MessagePlaceHolderManager;
import dev.aeroac.platform.api.manager.PlatformPluginManager;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.api.player.PlatformPlayerFactory;
import dev.aeroac.platform.api.scheduler.PlatformScheduler;
import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.platform.api.sender.SenderFactory;
import dev.aeroac.platform.bukkit.initables.BukkitBStats;
import dev.aeroac.platform.bukkit.initables.BukkitEventManager;
import dev.aeroac.platform.bukkit.initables.BukkitLuckPermsInitable;
import dev.aeroac.platform.bukkit.initables.BukkitTickEndEvent;
import dev.aeroac.platform.bukkit.manager.BukkitItemResetHandler;
import dev.aeroac.platform.bukkit.manager.BukkitMessagePlaceHolderManager;
import dev.aeroac.platform.bukkit.manager.BukkitCloudPlatformCommandArguments;
import dev.aeroac.platform.bukkit.manager.BukkitPermissionRegistrationManager;
import dev.aeroac.platform.bukkit.manager.BukkitPlatformPluginManager;
import dev.aeroac.platform.bukkit.player.BukkitPlatformPlayerFactory;
import dev.aeroac.platform.bukkit.scheduler.bukkit.BukkitPlatformScheduler;
import dev.aeroac.platform.bukkit.scheduler.folia.FoliaPlatformScheduler;
import dev.aeroac.platform.bukkit.sender.BukkitSenderFactory;
import dev.aeroac.platform.bukkit.utils.placeholder.PlaceholderAPIExpansion;
import dev.aeroac.utils.anticheat.LogUtil;
import dev.aeroac.utils.lazy.LazyHolder;
import com.github.retrooper.packetevents.PacketEventsAPI;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.brigadier.BrigadierSetting;
import org.incendo.cloud.brigadier.CloudBrigadierManager;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;

import java.io.File;

public final class AeroACBukkitLoaderPlugin extends JavaPlugin implements PlatformLoader {
    public static AeroACBukkitLoaderPlugin LOADER;

    private final LazyHolder<PlatformScheduler> scheduler = LazyHolder.simple(this::createScheduler);
    private final LazyHolder<PacketEventsAPI<?>> packetEvents = LazyHolder.simple(() -> SpigotPacketEventsBuilder.build(this));
    private final LazyHolder<BukkitSenderFactory> senderFactory = LazyHolder.simple(BukkitSenderFactory::new);
    private final LazyHolder<ItemResetHandler> itemResetHandler = LazyHolder.simple(BukkitItemResetHandler::new);
    private final LazyHolder<CommandService> commandService = LazyHolder.simple(this::createCommandService);
    private final CloudPlatformCommandArguments commandArguments = new BukkitCloudPlatformCommandArguments();

    @Getter private final PlatformPlayerFactory platformPlayerFactory = new BukkitPlatformPlayerFactory();
    @Getter private final PlatformPluginManager pluginManager = new BukkitPlatformPluginManager();
    @Getter private final GrimPlugin plugin;
    @Getter private final PlatformServer platformServer = new BukkitPlatformServer();
    @Getter private final MessagePlaceHolderManager messagePlaceHolderManager = new BukkitMessagePlaceHolderManager();
    @Getter private final BukkitPermissionRegistrationManager permissionManager = new BukkitPermissionRegistrationManager();

    public AeroACBukkitLoaderPlugin() {
        BukkitResolverRegistrar registrar = new BukkitResolverRegistrar();
        registrar.registerAll(AeroAPI.INSTANCE.getExtensionManager());
        this.plugin = registrar.resolvePlugin(this);
    }

    @Override
    public void onLoad() {
        LOADER = this;
        migrateLegacyDataFolder();
        AeroAPI.INSTANCE.load(this, this.getBukkitInitTasks());
    }

    /**
     * Makes {@code plugins/AeroAC} the one active data folder before anything reads a config.
     *
     * <p>The plugin name changed but an upgraded server still keeps its configuration, datasets and
     * bundles under {@code plugins/GrimAC}. This copies them across once — copy-only, never
     * overwriting what already exists in the active folder, and never modifying the old one, so a
     * rollback by restoring the previous jar still works. A conflict (the same path holding
     * different content in both folders) is not merged silently: the migration refuses, the active
     * folder is left untouched, and the conflicting paths are printed for the operator to resolve.
     *
     * <p>Runs on load, on the plugin thread, and touches only the file system — no HTTP, no scheduler.
     *
     * <p>Logs through this plugin's own logger rather than {@code LogUtil}. The migration has to run
     * before anything reads a config, which is before {@code AeroAPI} has a loader to hand a logger
     * out of; routing these lines through the API would make the first message of the first start
     * throw and take the whole plugin down with it.
     */
    private void migrateLegacyDataFolder() {
        File folder = getDataFolder();
        File pluginsDirectory = folder == null ? null : folder.getParentFile();
        if (pluginsDirectory == null) {
            getLogger().warning("Папка плагинов не определена; активная папка данных "
                    + "и перенос из старой папки не проверены.");
            return;
        }
        LegacyDataFolderMigration.Result migration =
                LegacyDataFolderMigration.migrate(pluginsDirectory.toPath());
        if (migration.ok()) {
            getLogger().info(migration.summary());
        } else {
            getLogger().severe(migration.summary());
        }
    }

    private Initable[] getBukkitInitTasks() {
        return new Initable[] {
                new ExemptOnlinePlayersOnReload(),
                new BukkitEventManager(),
                new dev.aeroac.platform.bukkit.admin.BukkitAdminGui(),
                new dev.aeroac.platform.bukkit.enforcement.BukkitBanPresenter(),
                new BukkitTickEndEvent(),
                new BukkitBStats(),
                new BukkitLuckPermsInitable(),
                (StartableInitable) () -> {
                    if (BukkitMessagePlaceHolderManager.hasPlaceholderAPI) {
                        new PlaceholderAPIExpansion().register();
                    }
                }
        };
    }

    @Override
    public void onEnable() {
        AeroAPI.INSTANCE.start();
    }

    @Override
    public void onDisable() {
        AeroAPI.INSTANCE.stop();
    }

    @Override
    public PlatformScheduler getScheduler() {
        return scheduler.get();
    }

    @Override
    public PacketEventsAPI<?> getPacketEvents() {
        return packetEvents.get();
    }

    @Override
    public ItemResetHandler getItemResetHandler() {
        return itemResetHandler.get();
    }

    @Override
    public CommandService getCommandService() {
        return commandService.get();
    }

    @Override
    public SenderFactory<CommandSender> getSenderFactory() {
        return senderFactory.get();
    }

    @Override
    @SuppressWarnings("removal")
    public void registerAPIService() {
        final AeroExternalAPI externalAPI = AeroAPI.INSTANCE.getExternalAPI();
        final EventBus eventBus = externalAPI.getEventBus();
        final ac.grim.grimac.api.plugin.GrimPlugin plugin = AeroAPI.INSTANCE.getGrimPlugin();

        // Bridge Grim events → legacy Bukkit Event API so pre-1.3 plugins that
        // listened for ac.grim.grimac.api.events.* Bukkit events keep working.
        // Typed channel subscriptions here are plugin-bound so they go away if
        // GrimAC itself is disabled.

        eventBus.get(ac.grim.grimac.api.event.events.GrimJoinEvent.class).onJoin(plugin, (user) -> {
            Bukkit.getPluginManager().callEvent(new ac.grim.grimac.api.events.GrimJoinEvent(user));
        });

        eventBus.get(ac.grim.grimac.api.event.events.GrimQuitEvent.class).onQuit(plugin, (user) -> {
            Bukkit.getPluginManager().callEvent(new ac.grim.grimac.api.events.GrimQuitEvent(user));
        });

        eventBus.get(ac.grim.grimac.api.event.events.GrimReloadEvent.class).onReload(plugin, (success) -> {
            Bukkit.getPluginManager().callEvent(new ac.grim.grimac.api.events.GrimReloadEvent(success));
        });

        eventBus.subscribe(plugin, ac.grim.grimac.api.event.events.FlagEvent.class, event -> {
            ac.grim.grimac.api.events.FlagEvent bukkitEvent =
                    new ac.grim.grimac.api.events.FlagEvent(event.getUser(), event.getCheck(), event::getVerbose);
            Bukkit.getPluginManager().callEvent(bukkitEvent);
            event.setCancelled(event.isCancelled() || bukkitEvent.isCancelled());
        }, 0, false, AeroACBukkitLoaderPlugin.class);

        eventBus.get(ac.grim.grimac.api.event.events.CommandExecuteEvent.class).onCommandExecute(plugin, (user, check, verbose, command, cancelled) -> {
            ac.grim.grimac.api.events.CommandExecuteEvent bukkitEvent =
                    new ac.grim.grimac.api.events.CommandExecuteEvent(user, check, verbose, command);
            Bukkit.getPluginManager().callEvent(bukkitEvent);
            return cancelled || bukkitEvent.isCancelled();
        });

        eventBus.get(ac.grim.grimac.api.event.events.CompletePredictionEvent.class).onCompletePrediction(plugin, (user, check, offset, cancelled) -> {
            // Legacy Bukkit event has a verbose field that the new channel event does not; pass empty.
            ac.grim.grimac.api.events.CompletePredictionEvent bukkitEvent =
                    new ac.grim.grimac.api.events.CompletePredictionEvent(user, check, "", offset);
            Bukkit.getPluginManager().callEvent(bukkitEvent);
            return cancelled || bukkitEvent.isCancelled();
        });

        GrimAPIProvider.init(externalAPI);
        Bukkit.getServicesManager().register(GrimAbstractAPI.class, externalAPI, this, ServicePriority.Normal);
    }

    private PlatformScheduler createScheduler() {
        return AeroAPI.INSTANCE.getPlatform() == Platform.FOLIA ? new FoliaPlatformScheduler() : new BukkitPlatformScheduler();
    }

    private CommandService createCommandService() {
        try {
            return new CloudCommandService(this::createCloudCommandManager, commandArguments);
        } catch (Throwable t) {
            LogUtil.warn("CRITICAL: Failed to initialize Command Framework. " +
                    "Aero AC will continue to run with no commands.", t);
            return () -> {};
        }
    }

    private CommandManager<Sender> createCloudCommandManager() {
        LegacyPaperCommandManager<Sender> manager = new LegacyPaperCommandManager<>(
                this,
                ExecutionCoordinator.simpleCoordinator(),
                senderFactory.get()
        );
        if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            try {
                manager.registerBrigadier();
                CloudBrigadierManager<Sender, ?> cbm = manager.brigadierManager();
                cbm.settings().set(BrigadierSetting.FORCE_EXECUTABLE, true);
            } catch (Throwable t) {
                LogUtil.error("Failed to register Brigadier native completions. Falling back to standard completions.", t);
            }
        } else if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            manager.registerAsynchronousCompletions();
        }
        return manager;
    }

    public BukkitSenderFactory getBukkitSenderFactory() {
        return senderFactory.get();
    }
}
