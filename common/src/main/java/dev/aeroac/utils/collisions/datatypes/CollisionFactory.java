package dev.aeroac.utils.collisions.datatypes;

import dev.aeroac.player.AeroPlayer;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;

public interface CollisionFactory {
    CollisionBox fetch(AeroPlayer player, ClientVersion version, WrappedBlockState block, int x, int y, int z);
}
