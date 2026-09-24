package dev.aeroac.platform.fabric.mc1205;

import dev.aeroac.platform.fabric.AeroACFabricIntermediaryLoaderPlugin;
import dev.aeroac.platform.fabric.mc1171.player.Fabric1170PlatformPlayer;
import dev.aeroac.platform.fabric.mc1194.Fabric1190PlatformServer;
import dev.aeroac.platform.fabric.mc1194.AeroACFabric1190LoaderPlugin;
import dev.aeroac.platform.fabric.mc1194.player.Fabric1193PlatformInventory;
import dev.aeroac.platform.fabric.mc1205.convert.Fabric1200MessageUtil;
import dev.aeroac.platform.fabric.mc1205.convert.Fabric1205ConversionUtil;
import dev.aeroac.platform.fabric.mc1194.entity.Fabric1194AeroEntity;
import dev.aeroac.platform.fabric.mc1205.player.Fabric1202PlatformPlayer;
import dev.aeroac.platform.fabric.mc1161.util.convert.Fabric1140ConversionUtil;
import dev.aeroac.platform.fabric.player.FabricPlatformPlayerFactory;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import io.github.retrooper.packetevents.factory.fabric.FabricPacketEventsAPI;

public class AeroACFabric1200LoaderPlugin extends AeroACFabric1190LoaderPlugin {

    public AeroACFabric1200LoaderPlugin() {
        super(
                AeroACFabricIntermediaryLoaderPlugin::createCommandArguments,
                new FabricPlatformPlayerFactory(
                        FabricPacketEventsAPI.getServerAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_20_1)
                                ? Fabric1202PlatformPlayer::new : Fabric1170PlatformPlayer::new,
                        Fabric1194AeroEntity::new,
                        Fabric1193PlatformInventory::new
                ),
                FabricPacketEventsAPI.getServerAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_20_2)
                        ? new Fabric1203PlatformServer() : new Fabric1190PlatformServer(),
                new Fabric1200MessageUtil(),
                FabricPacketEventsAPI.getServerAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_20_4)
                        ? new Fabric1205ConversionUtil() : new Fabric1140ConversionUtil()
        );
    }

    @Override
    public ServerVersion getNativeVersion() {
        return ServerVersion.V_1_20_5;
    }
}
