package dev.aeroac.platform.fabric.mc1161;

import dev.aeroac.platform.fabric.AbstractFabricPlatformServer;
import dev.aeroac.platform.fabric.AeroACFabricIntermediaryLoaderPlugin;
import dev.aeroac.platform.fabric.mc1161.entity.Fabric1161AeroEntity;
import dev.aeroac.platform.fabric.mc1161.player.Fabric1161PlatformInventory;
import dev.aeroac.platform.fabric.mc1161.player.Fabric1161PlatformPlayer;
import dev.aeroac.platform.fabric.mc1161.util.convert.Fabric1140ConversionUtil;
import dev.aeroac.platform.fabric.mc1161.util.convert.Fabric1161MessageUtil;
import dev.aeroac.platform.fabric.player.FabricPlatformPlayerFactory;
import dev.aeroac.platform.fabric.utils.convert.IFabricConversionUtil;
import dev.aeroac.platform.fabric.utils.message.IFabricMessageUtil;
import com.github.retrooper.packetevents.manager.server.ServerVersion;

public class AeroACFabric1161LoaderPlugin extends AeroACFabricIntermediaryLoaderPlugin {

    public AeroACFabric1161LoaderPlugin() {
        this(
            new FabricPlatformPlayerFactory(
                Fabric1161PlatformPlayer::new,
                Fabric1161AeroEntity::new,
                Fabric1161PlatformInventory::new
            ),
            new Fabric1140PlatformServer(),
            new Fabric1161MessageUtil(),
            new Fabric1140ConversionUtil()
        );
    }

    protected AeroACFabric1161LoaderPlugin(
            FabricPlatformPlayerFactory playerFactory,
            AbstractFabricPlatformServer platformServer,
            IFabricMessageUtil fabricMessageUtil,
            IFabricConversionUtil fabricConversionUtil
    ) {
        super(AeroACFabricIntermediaryLoaderPlugin::createCommandArguments,
            playerFactory,
            platformServer,
            fabricMessageUtil,
            fabricConversionUtil
        );
    }

    @Override
    public ServerVersion getNativeVersion() {
        return ServerVersion.V_1_16_1;
    }
}
