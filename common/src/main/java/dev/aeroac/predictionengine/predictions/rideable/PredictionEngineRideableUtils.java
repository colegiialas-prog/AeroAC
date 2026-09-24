package dev.aeroac.predictionengine.predictions.rideable;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.predictionengine.predictions.PredictionEngineNormal;
import dev.aeroac.predictionengine.predictions.input.Input;
import dev.aeroac.predictionengine.predictions.input.InputTransformer;
import dev.aeroac.utils.data.IntToObjectPair;
import dev.aeroac.utils.data.VectorData;
import dev.aeroac.utils.data.packetentity.JumpableEntity;
import dev.aeroac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@UtilityClass
public final class PredictionEngineRideableUtils {

    public static Set<VectorData> handleJumps(AeroPlayer player, Set<VectorData> possibleVectors) {
        if (!(player.compensatedEntities.self.getRiding() instanceof JumpableEntity jumpable))
            return possibleVectors;

        // TODO: onGround can desync if it's first riding tick
        jumpable.executeJump(player, possibleVectors);

        // More jumping stuff
        boolean legacyJumpingMechanics = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_19_3);
        boolean onGround = legacyJumpingMechanics
                ? player.clientControlledVerticalCollision
                : player.lastOnGround;
        if (onGround) {
            if (legacyJumpingMechanics) {
                jumpable.setJumpPower(0.0F);
            }

            jumpable.setJumping(false);
        }

        return possibleVectors;
    }

    public static List<VectorData> applyInputsToVelocityPossibilities(Input movementVector, AeroPlayer player, Set<VectorData> possibleVectors, float speed) {
        List<VectorData> returnVectors = new ArrayList<>();

        InputTransformer<?> inputTransformer = InputTransformer.getTransformer(player);
        for (VectorData possibleLastTickOutput : possibleVectors) {
            for (int applyStuckSpeed = 1; applyStuckSpeed >= 0; applyStuckSpeed--) {
                if (applyStuckSpeed == 0 && player.isForceStuckSpeed()) break;

                VectorData result = new VectorData(possibleLastTickOutput.vector.clone().add(inputTransformer.getMovementResultFromInput(player, movementVector, speed, player.yaw)), possibleLastTickOutput, VectorData.VectorType.InputResult);
                result.input = new Vector3dm(player.vehicleData.vehicleForward, 0, player.vehicleData.vehicleHorizontal);
                Vector3dm vector = result.vector.clone();
                if (applyStuckSpeed != 0) vector.multiply(player.stuckSpeedMultiplier);
                result = result.returnNewModified(vector, VectorData.VectorType.StuckMultiplier);
                result = result.returnNewModified(new PredictionEngineNormal().handleOnClimbable(result.vector.clone(), player), VectorData.VectorType.Climbable);
                returnVectors.add(result);

                // This is the laziest way to reduce false positives such as horse rearing
                // No bypasses can ever be derived from this, so why not?
                result = new VectorData(possibleLastTickOutput.vector.clone(), possibleLastTickOutput, VectorData.VectorType.InputResult);
                result.input = new Vector3dm(player.vehicleData.vehicleForward, 0, player.vehicleData.vehicleHorizontal);
                vector = result.vector.clone();
                if (applyStuckSpeed != 0) vector.multiply(player.stuckSpeedMultiplier);
                result = result.returnNewModified(vector, VectorData.VectorType.StuckMultiplier);
                result = result.returnNewModified(new PredictionEngineNormal().handleOnClimbable(result.vector.clone(), player), VectorData.VectorType.Climbable);
                returnVectors.add(result);
            }
        }

        return returnVectors;
    }

    public static void applyPendingJumps(AeroPlayer player) {
        IntToObjectPair<JumpableEntity> pendingJump;
        while ((pendingJump = player.vehicleData.pendingJumps.poll()) != null) {
            JumpableEntity jumpable = pendingJump.second();
            if (jumpable.canPlayerJump(player)) {
                int jumpBoost = pendingJump.first();
                if (jumpBoost < 0) jumpBoost = 0;
                if (jumpBoost >= 90) {
                    jumpable.setJumpPower(1);
                } else {
                    jumpable.setJumpPower(0.4F + 0.4F * jumpBoost / 90.0F);
                }
            }
        }
    }

}
