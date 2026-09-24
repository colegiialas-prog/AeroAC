package dev.aeroac.predictionengine.predictions.rideable;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.predictionengine.predictions.PredictionEngineWaterLegacy;
import dev.aeroac.predictionengine.predictions.input.Input;
import dev.aeroac.utils.data.VectorData;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class PredictionEngineRideableWaterLegacy extends PredictionEngineWaterLegacy {
    private final Input movementVector;

    @Override
    public void addJumpsToPossibilities(AeroPlayer player, Set<VectorData> existingVelocities) {
        PredictionEngineRideableUtils.handleJumps(player, existingVelocities);
    }

    @Override
    public List<VectorData> applyInputsToVelocityPossibilities(AeroPlayer player, Set<VectorData> possibleVectors, float speed) {
        return PredictionEngineRideableUtils.applyInputsToVelocityPossibilities(movementVector, player, possibleVectors, speed);
    }
}
