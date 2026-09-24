package dev.aeroac.platform.fabric;

import dev.aeroac.platform.fabric.manager.FabricItemResetHandler;
import dev.aeroac.platform.fabric.manager.FabricCloudPlatformCommandArguments;
import dev.aeroac.platform.fabric.manager.FabricPermissionRegistrationManager;
import dev.aeroac.platform.fabric.command.FabricPlayerSelectorParser;
import dev.aeroac.platform.fabric.player.FabricPlatformPlayerFactory;
import dev.aeroac.platform.fabric.scheduler.FabricPlatformScheduler;
import dev.aeroac.platform.fabric.sender.AbstractFabricSenderFactory;
import dev.aeroac.platform.fabric.sender.FabricOfficialSenderFactory;
import me.lucko.fabric.api.permissions.v0.Permissions;
import dev.aeroac.platform.fabric.utils.FabricOfficialPolymerHook;
import dev.aeroac.platform.fabric.utils.convert.IFabricConversionUtil;
import dev.aeroac.platform.fabric.utils.message.IFabricMessageUtil;
import dev.aeroac.utils.lazy.LazyHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public abstract class AeroACFabricOfficialLoaderPlugin extends AbstractAeroACFabricLoaderPlugin<
        FabricPlatformPlayerFactory,
        AbstractFabricPlatformServer,
        FabricPlatformScheduler,
        FabricOfficialSenderFactory,
        FabricItemResetHandler,
        FabricCloudPlatformCommandArguments
        > {
    public static MinecraftServer FABRIC_SERVER;
    public static AeroACFabricOfficialLoaderPlugin LOADER;

    public AeroACFabricOfficialLoaderPlugin(
            FabricPlatformPlayerFactory playerFactory,
            AbstractFabricPlatformServer platformServer,
            IFabricMessageUtil fabricMessageUtil,
            IFabricConversionUtil fabricConversionUtil
    ) {
        super(
                LazyHolder.simple(FabricPlatformScheduler::new),
                LazyHolder.simple(FabricOfficialSenderFactory::new),
                LazyHolder.simple(() -> new FabricItemResetHandler(fabricConversionUtil)),
                LazyHolder.simple(AeroACFabricOfficialLoaderPlugin::createCommandArguments),
                LazyHolder.simple(() -> new FabricPermissionRegistrationManager(
                        LOADER.getFabricSenderFactory(),
                        name -> {
                            if (AbstractFabricSenderFactory.HAS_PERMISSIONS_API) {
                                Permissions.check(FABRIC_SERVER.createCommandSourceStack(), name);
                            }
                        })),
                playerFactory,
                platformServer,
                fabricMessageUtil,
                fabricConversionUtil
        );
        FabricPlatformServices.configure(
                playerFactory::getPlatformInventory,
                playerFactory::getPlatformEntity,
                player -> FabricOfficialPolymerHook.createTranslator((ServerPlayer) player),
                fabricMessageUtil::textLiteral,
                platformServer::getProfileByName,
                fabricConversionUtil
        );
    }

    public FabricOfficialSenderFactory getFabricSenderFactory() {
        return senderFactory.get();
    }

    public static FabricCloudPlatformCommandArguments createCommandArguments() {
        return new FabricCloudPlatformCommandArguments(new FabricPlayerSelectorParser<>(
                selector -> LOADER.getFabricSenderFactory().wrap(selector.single().createCommandSourceStack()),
                selector -> selector.inputString()
        ));
    }

}
