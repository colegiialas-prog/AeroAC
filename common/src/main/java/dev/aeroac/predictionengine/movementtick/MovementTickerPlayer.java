package dev.aeroac.predictionengine.movementtick;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.predictionengine.predictions.PredictionEngineLava;
import dev.aeroac.predictionengine.predictions.PredictionEngineNormal;
import dev.aeroac.predictionengine.predictions.PredictionEngineWater;
import dev.aeroac.predictionengine.predictions.PredictionEngineWaterLegacy;
import dev.aeroac.utils.nmsutil.BlockProperties;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

public class MovementTickerPlayer extends MovementTicker {
    public MovementTickerPlayer(AeroPlayer player) {
        super(player);
    }

    @Override
    public void doWaterMove(float swimSpeed, boolean isFalling, float swimFriction) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_13)) {
            new PredictionEngineWater().guessBestMovement(swimSpeed, player, isFalling, player.gravity, swimFriction);
        } else {
            new PredictionEngineWaterLegacy().guessBestMovement(swimSpeed, player, swimFriction);
        }
    }

    @Override
    public void doLavaMove() {
        new PredictionEngineLava().guessBestMovement(0.02F, player);
    }

    @Override
    public void doNormalMove(float blockFriction) {
        new PredictionEngineNormal().guessBestMovement(BlockProperties.getFrictionInfluencedSpeed(blockFriction, player), player);
    }
}
