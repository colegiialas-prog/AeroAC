package dev.aeroac.predictionengine.blockeffects.impl;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.predictionengine.blockeffects.BlockCollisions;
import dev.aeroac.predictionengine.blockeffects.BlockEffectsResolver;
import dev.aeroac.predictionengine.blockeffects.BlockStepVisitor;
import dev.aeroac.utils.collisions.datatypes.SimpleCollisionBox;
import dev.aeroac.utils.math.AeroMath;
import dev.aeroac.utils.math.Vector3dm;
import dev.aeroac.utils.nmsutil.Collisions;
import dev.aeroac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.List;
import java.util.Optional;

// 1.21.5
public class BlockEffectsResolverV1_21_5 implements BlockEffectsResolver {

    public static final BlockEffectsResolver INSTANCE = new BlockEffectsResolverV1_21_5();

    @Override
    public void applyEffectsFromBlocks(AeroPlayer player, Vector3dm clientVelocity, boolean onlyApplyVelocity, List<AeroPlayer.Movement> movements) {
        LongSet visitedBlocks = player.visitedBlocks;

        for (AeroPlayer.Movement movement : movements) {
            Vector3d from = movement.from();
            Vector3d to = movement.to().subtract(movement.from());
            if (movement.axisIndependant() && to.lengthSquared() > 0.0) {
                for (Collisions.Axis axis : BlockCollisions.axisStepOrder(to)) {
                    double value = axis.get(to);
                    if (value != 0.0) {
                        Vector3d vector = BlockCollisions.relative(from, axis.getPositive(), value);
                        checkInsideBlocks(player, clientVelocity, onlyApplyVelocity, from, vector, visitedBlocks);
                        from = vector;
                    }
                }
            } else {
                checkInsideBlocks(player, clientVelocity, onlyApplyVelocity, movement.from(), movement.to(), visitedBlocks);
            }
        }

        visitedBlocks.clear();
    }

    private static void checkInsideBlocks(AeroPlayer player, Vector3dm clientVelocity, boolean onlyApplyVelocity, Vector3d from, Vector3d to, LongSet visitedBlocks) {
        SimpleCollisionBox boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, to.x, to.y, to.z).expand(-1.0E-5F);
        forEachBlockIntersectedBetween(from, to, boundingBox, (blockPos, i) -> {
            WrappedBlockState blockState = player.compensatedWorld.getBlock(blockPos);
            StateType blockType = blockState.getType();

            if (blockType.isAir()) {
                return true;
            }

            if (visitedBlocks.add(AeroMath.asLong(blockPos))) {
                Collisions.onInsideBlock(player, clientVelocity, onlyApplyVelocity, blockType, blockState, blockPos.x, blockPos.y, blockPos.z, true);
            }

            return true;
        });
    }

    private static void forEachBlockIntersectedBetween(Vector3d start, Vector3d end, SimpleCollisionBox boundingBox, BlockStepVisitor blockStepVisitor) {
        Vector3d direction = end.subtract(start);
        if (!(direction.lengthSquared() < AeroMath.square(0.99999F))) {
            LongSet alreadyVisited = new LongOpenHashSet();
            Vector3d boxMinPosition = boundingBox.min().toVector3d();
            Vector3d subtractedMinPosition = boxMinPosition.subtract(direction);
            int iterationCount = addCollisionsAlongTravel(alreadyVisited, subtractedMinPosition, boxMinPosition, boundingBox, blockStepVisitor);

            for (Vector3i blockPos : SimpleCollisionBox.betweenClosed(boundingBox)) {
                if (!alreadyVisited.contains(AeroMath.asLong(blockPos))) {
                    blockStepVisitor.visit(blockPos, iterationCount + 1);
                }
            }
        } else {
            for (Vector3i blockPos : SimpleCollisionBox.betweenClosed(boundingBox)) {
                blockStepVisitor.visit(blockPos, 0);
            }
        }
    }

    private static int addCollisionsAlongTravel(LongSet alreadyVisited, Vector3d start, Vector3d end, SimpleCollisionBox boundingBox, BlockStepVisitor blockStepVisitor) {
        Vector3d direction = end.subtract(start);
        int currentX = AeroMath.floor(start.x);
        int currentY = AeroMath.floor(start.y);
        int currentZ = AeroMath.floor(start.z);
        int stepX = AeroMath.sign(direction.x);
        int stepY = AeroMath.sign(direction.y);
        int stepZ = AeroMath.sign(direction.z);
        double tMaxX = stepX == 0 ? Double.MAX_VALUE : stepX / direction.x;
        double tMaxY = stepY == 0 ? Double.MAX_VALUE : stepY / direction.y;
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : stepZ / direction.z;
        double tDeltaX = tMaxX * (stepX > 0 ? 1.0 - AeroMath.frac(start.x) : AeroMath.frac(start.x));
        double tDeltaY = tMaxY * (stepY > 0 ? 1.0 - AeroMath.frac(start.y) : AeroMath.frac(start.y));
        double tDeltaZ = tMaxZ * (stepZ > 0 ? 1.0 - AeroMath.frac(start.z) : AeroMath.frac(start.z));
        int iterationCount = 0;

        while (tDeltaX <= 1.0 || tDeltaY <= 1.0 || tDeltaZ <= 1.0) {
            if (tDeltaX < tDeltaY) {
                if (tDeltaX < tDeltaZ) {
                    currentX += stepX;
                    tDeltaX += tMaxX;
                } else {
                    currentZ += stepZ;
                    tDeltaZ += tMaxZ;
                }
            } else if (tDeltaY < tDeltaZ) {
                currentY += stepY;
                tDeltaY += tMaxY;
            } else {
                currentZ += stepZ;
                tDeltaZ += tMaxZ;
            }

            if (iterationCount++ > 16) {
                break;
            }

            Optional<Vector3d> collisionPoint = BlockCollisions.clip(currentX, currentY, currentZ, currentX + 1, currentY + 1, currentZ + 1, start, end);
            if (!collisionPoint.isEmpty()) {
                Vector3d collisionVec = collisionPoint.get();
                double clampedX = AeroMath.clamp(collisionVec.x, currentX + 1.0E-5F, currentX + 1.0 - 1.0E-5F);
                double clampedY = AeroMath.clamp(collisionVec.y, currentY + 1.0E-5F, currentY + 1.0 - 1.0E-5F);
                double clampedZ = AeroMath.clamp(collisionVec.z, currentZ + 1.0E-5F, currentZ + 1.0 - 1.0E-5F);
                int endX = AeroMath.floor(clampedX + boundingBox.getXSize());
                int endY = AeroMath.floor(clampedY + boundingBox.getYSize());
                int endZ = AeroMath.floor(clampedZ + boundingBox.getZSize());

                for (int x = currentX; x <= endX; x++) {
                    for (int y = currentY; y <= endY; y++) {
                        for (int z = currentZ; z <= endZ; z++) {
                            if (alreadyVisited.add(AeroMath.asLong(x, y, z))) {
                                blockStepVisitor.visit(new Vector3i(x, y, z), iterationCount);
                            }
                        }
                    }
                }
            }
        }

        return iterationCount;
    }

}
