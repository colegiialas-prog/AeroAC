package dev.aeroac.predictionengine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EntityPushBoundsTest {

    // Entity#push(Entity) as run on the client, returning the velocity added to the player
    private static double[] vanillaPush(double playerX, double playerZ, double entityX, double entityZ) {
        double d = playerX - entityX;
        double e = playerZ - entityZ;
        double f = Math.max(Math.abs(d), Math.abs(e));
        if (f < 0.01F) return new double[]{0, 0};
        f = Math.sqrt(f);
        d /= f;
        e /= f;
        double g = Math.min(1.0, 1.0 / f);
        return new double[]{d * g * 0.05F, e * g * 0.05F};
    }

    private static EntityPushBounds single(double playerMinX, double playerMaxX, double playerMinZ, double playerMaxZ,
                                           double entityMinX, double entityMaxX, double entityMinZ, double entityMaxZ) {
        return new EntityPushBounds.Builder().add(
                EntityPushBounds.pushSide(playerMinX, playerMaxX, entityMinX, entityMaxX),
                EntityPushBounds.pushSide(playerMinZ, playerMaxZ, entityMinZ, entityMaxZ)).build();
    }

    @Test void everyVanillaPushDirectionIsAllowed() {
        Random random = new Random(7);
        for (int i = 0; i < 200_000; i++) {
            double playerMinX = random.nextDouble() * 2 - 1, playerMaxX = playerMinX + random.nextDouble() * 0.4;
            double playerMinZ = random.nextDouble() * 2 - 1, playerMaxZ = playerMinZ + random.nextDouble() * 0.4;
            double entityMinX = random.nextDouble() * 2 - 1, entityMaxX = entityMinX + random.nextDouble() * 0.6;
            double entityMinZ = random.nextDouble() * 2 - 1, entityMaxZ = entityMinZ + random.nextDouble() * 0.6;
            EntityPushBounds bounds = single(playerMinX, playerMaxX, playerMinZ, playerMaxZ, entityMinX, entityMaxX, entityMinZ, entityMaxZ);

            double playerX = playerMinX + random.nextDouble() * (playerMaxX - playerMinX);
            double playerZ = playerMinZ + random.nextDouble() * (playerMaxZ - playerMinZ);
            double entityX = entityMinX + random.nextDouble() * (entityMaxX - entityMinX);
            double entityZ = entityMinZ + random.nextDouble() * (entityMaxZ - entityMinZ);
            double[] push = vanillaPush(playerX, playerZ, entityX, entityZ);

            if (push[0] > 0) assertEquals(1, bounds.positiveX(), "x+ push " + i);
            if (push[0] < 0) assertEquals(1, bounds.negativeX(), "x- push " + i);
            if (push[1] > 0) assertEquals(1, bounds.positiveZ(), "z+ push " + i);
            if (push[1] < 0) assertEquals(1, bounds.negativeZ(), "z- push " + i);
        }
    }

    @Test void chasingTowardsAnEntityGetsNoPushTowardsIt() {
        // Player runs +x into a target standing 0.5 blocks ahead, the "collide" speed boost direction
        EntityPushBounds bounds = single(-0.03, 0.28 + 0.03, -0.03, 0.03, 0.5 - 0.03125, 0.5 + 0.03125, -0.03125, 0.03125);
        assertEquals(0, bounds.positiveX());
        assertEquals(1, bounds.negativeX());
        // Sideways the centres overlap, so either side stays possible
        assertEquals(1, bounds.positiveZ());
        assertEquals(1, bounds.negativeZ());
    }

    @Test void overlappingCentresAllowBothSides() {
        EntityPushBounds bounds = single(0, 0.1, 0, 0.1, 0.05, 0.3, 0.05, 0.3);
        assertEquals(new EntityPushBounds(1, 1, 1, 1), bounds);
    }

    @Test void entitiesAddUpPerDirection() {
        EntityPushBounds bounds = new EntityPushBounds.Builder()
                .add(1, 0)
                .add(1, -1)
                .add(-1, 1)
                .build();
        assertEquals(new EntityPushBounds(1, 2, 2, 2), bounds);
    }

    @Test void windowTakesTheLargestCountPerDirection() {
        EntityPushBounds bounds = EntityPushBounds.max(List.of(
                new EntityPushBounds(0, 2, 1, 0),
                new EntityPushBounds(1, 0, 0, 3),
                EntityPushBounds.NONE));
        assertEquals(new EntityPushBounds(1, 2, 1, 3), bounds);
        assertEquals(EntityPushBounds.NONE, EntityPushBounds.max(List.of(EntityPushBounds.NONE)));
    }
}
