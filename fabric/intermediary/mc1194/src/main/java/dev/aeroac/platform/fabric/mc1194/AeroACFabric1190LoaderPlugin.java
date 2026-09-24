package dev.aeroac.platform.fabric.mc1194;

import dev.aeroac.platform.fabric.AbstractFabricPlatformServer;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.fabric.AeroACFabricIntermediaryLoaderPlugin;
import dev.aeroac.platform.fabric.mc1171.AeroACFabric1170LoaderPlugin;
import dev.aeroac.platform.fabric.mc1171.player.Fabric1170PlatformPlayer;
import dev.aeroac.platform.fabric.mc1194.convert.Fabric1190MessageUtil;
import dev.aeroac.platform.fabric.mc1194.entity.Fabric1194AeroEntity;
import dev.aeroac.platform.fabric.mc1194.player.Fabric1193PlatformInventory;
import dev.aeroac.platform.fabric.mc1161.player.Fabric1161PlatformInventory;
import dev.aeroac.platform.fabric.mc1161.util.convert.Fabric1140ConversionUtil;
import dev.aeroac.platform.fabric.player.FabricPlatformPlayerFactory;
import dev.aeroac.platform.fabric.utils.convert.IFabricConversionUtil;
import dev.aeroac.platform.fabric.utils.message.IFabricMessageUtil;
import dev.aeroac.utils.lazy.LazyHolder;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;


public class AeroACFabric1190LoaderPlugin extends AeroACFabric1170LoaderPlugin {

    public AeroACFabric1190LoaderPlugin() {
        this(
            AeroACFabricIntermediaryLoaderPlugin::createCommandArguments,
            new FabricPlatformPlayerFactory(
                    Fabric1170PlatformPlayer::new,
                    Fabric1194AeroEntity::new,
                    PacketEvents.getAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_19_2)
                            ? Fabric1193PlatformInventory::new : Fabric1161PlatformInventory::new
            ),
            new Fabric1190PlatformServer(),
            new Fabric1190MessageUtil(),
            new Fabric1140ConversionUtil()
        );
    }

    protected AeroACFabric1190LoaderPlugin(
            LazyHolder<CloudPlatformCommandArguments> commandArguments,
            FabricPlatformPlayerFactory platformPlayerFactory,
            AbstractFabricPlatformServer platformServer,
            IFabricMessageUtil fabricMessageUtil,
            IFabricConversionUtil fabricConversionUtil) {
        super(commandArguments, platformPlayerFactory, platformServer, fabricMessageUtil, fabricConversionUtil);
    }

    @Override
    public ServerVersion getNativeVersion() {
        return ServerVersion.V_1_19_4;
    }
}
