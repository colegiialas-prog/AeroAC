package dev.aeroac.platform.fabric.mc1171;

import dev.aeroac.platform.fabric.AbstractFabricPlatformServer;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.fabric.AeroACFabricIntermediaryLoaderPlugin;
import dev.aeroac.platform.fabric.mc1171.player.Fabric1170PlatformPlayer;
import dev.aeroac.platform.fabric.mc1161.Fabric1140PlatformServer;
import dev.aeroac.platform.fabric.mc1161.player.Fabric1161PlatformInventory;
import dev.aeroac.platform.fabric.mc1171.entity.Fabric1170AeroEntity;
import dev.aeroac.platform.fabric.mc1161.util.convert.Fabric1140ConversionUtil;
import dev.aeroac.platform.fabric.mc1161.util.convert.Fabric1161MessageUtil;
import dev.aeroac.platform.fabric.player.FabricPlatformPlayerFactory;
import dev.aeroac.platform.fabric.utils.convert.IFabricConversionUtil;
import dev.aeroac.platform.fabric.utils.message.IFabricMessageUtil;
import dev.aeroac.utils.lazy.LazyHolder;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;


public class AeroACFabric1170LoaderPlugin extends AeroACFabricIntermediaryLoaderPlugin {

    public AeroACFabric1170LoaderPlugin() {
        this(AeroACFabricIntermediaryLoaderPlugin::createCommandArguments,
                new FabricPlatformPlayerFactory(
                        Fabric1170PlatformPlayer::new,
                        Fabric1170AeroEntity::new,
                        Fabric1161PlatformInventory::new
                ),
                PacketEvents.getAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_17)
                        ? new Fabric1171PlatformServer() : new Fabric1140PlatformServer(),
                new Fabric1161MessageUtil(),
                new Fabric1140ConversionUtil()
        );
    }

    protected AeroACFabric1170LoaderPlugin(LazyHolder<CloudPlatformCommandArguments> commandArguments,
                                           FabricPlatformPlayerFactory playerFactory,
                                           AbstractFabricPlatformServer platformServer,
                                           IFabricMessageUtil fabricMessageUtil,
                                           IFabricConversionUtil fabricConversionUtil) {
        super(
                commandArguments,
                playerFactory,
                platformServer,
                fabricMessageUtil,
                fabricConversionUtil
        );
    }

    @Override
    public ServerVersion getNativeVersion() {
        return ServerVersion.V_1_17_1;
    }
}
