package dev.aeroac.platform.fabric.mc1171.player;

import dev.aeroac.platform.fabric.mc1161.player.Fabric1161PlatformPlayer;
import dev.aeroac.platform.fabric.utils.convert.FabricIntermediaryConversionUtil;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import net.minecraft.server.level.ServerPlayer;

public class Fabric1170PlatformPlayer extends Fabric1161PlatformPlayer {
    public Fabric1170PlatformPlayer(ServerPlayer player) {
        super(player);
    }

    @Override
    public void setGameMode(GameMode gameMode) {
        serverPlayer().setGameMode(FabricIntermediaryConversionUtil.toFabricGameMode(gameMode));
    }
}
