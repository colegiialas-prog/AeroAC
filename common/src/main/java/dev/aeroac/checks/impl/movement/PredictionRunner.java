package dev.aeroac.checks.impl.movement;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.type.PositionCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.PositionUpdate;

public class PredictionRunner extends Check implements PositionCheck {
    public PredictionRunner(AeroPlayer playerData) {
        super(playerData);
    }

    @Override
    public void onPositionUpdate(final PositionUpdate positionUpdate) {
        if (!player.inVehicle()) {
            player.movementCheckRunner.processAndCheckMovementPacket(positionUpdate);
        }
    }
}
