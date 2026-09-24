package dev.aeroac.predictionengine.blockeffects;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.math.Vector3dm;

import java.util.List;

public interface BlockEffectsResolver {

    void applyEffectsFromBlocks(AeroPlayer player, Vector3dm clientVelocity, boolean onlyApplyVelocity, List<AeroPlayer.Movement> movements);

}
