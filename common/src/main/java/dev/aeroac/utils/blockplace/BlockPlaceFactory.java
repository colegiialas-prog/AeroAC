package dev.aeroac.utils.blockplace;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.BlockPlace;

public interface BlockPlaceFactory {
    void applyBlockPlaceToWorld(AeroPlayer player, BlockPlace place);
}
