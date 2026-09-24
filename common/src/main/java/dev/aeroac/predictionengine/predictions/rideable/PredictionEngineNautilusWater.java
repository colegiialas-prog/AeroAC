package dev.aeroac.predictionengine.predictions.rideable;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.predictionengine.predictions.PredictionEngine;
import dev.aeroac.predictionengine.predictions.input.Input;
import dev.aeroac.utils.data.VectorData;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class PredictionEngineNautilusWater extends PredictionEngine {
    private final Input movementVector;
    private final double multiplier;

    @Override
    public void endOfTick(AeroPlayer player, double delta) {
        for (VectorData vector : player.getPossibleVelocitiesMinusKnockback()) {
            vector.vector.setX(vector.vector.getX() * multiplier);
            vector.vector.setY(vector.vector.getY() * multiplier);
            vector.vector.setZ(vector.vector.getZ() * multiplier);
        }
    }

    @Override
    public void addJumpsToPossibilities(AeroPlayer player, Set<VectorData> existingVelocities) {
        PredictionEngineRideableUtils.handleJumps(player, existingVelocities);
    }

    @Override
    public List<VectorData> applyInputsToVelocityPossibilities(AeroPlayer player, Set<VectorData> possibleVectors, float speed) {
        return PredictionEngineRideableUtils.applyInputsToVelocityPossibilities(movementVector, player, possibleVectors, speed);
    }

}
