package dev.aeroac.predictionengine;

import java.util.Collection;

/**
 * How many pushable entities could have pushed the player towards each horizontal direction.
 * <p>
 * Vanilla's {@code Entity#push(Entity)} moves the player straight away from the other entity's
 * centre, so an entity whose centre is known to lie on one side of the player on an axis can only
 * push the player towards the other side of that axis. Counting both directions for every nearby
 * entity is what let "collide" speed modules add the push leniency towards the entity they chase.
 */
public record EntityPushBounds(int negativeX, int positiveX, int negativeZ, int positiveZ) {
    public static final EntityPushBounds NONE = new EntityPushBounds(0, 0, 0, 0);

    /**
     * @return 1 if the entity can only push the player towards positive coordinates on this axis,
     * -1 if only towards negative ones, 0 if the sides overlap and either is possible
     */
    public static int pushSide(double playerMin, double playerMax, double entityMin, double entityMax) {
        if (playerMin > entityMax) return 1;
        if (playerMax < entityMin) return -1;
        return 0;
    }

    public static EntityPushBounds max(Collection<EntityPushBounds> window) {
        int negativeX = 0, positiveX = 0, negativeZ = 0, positiveZ = 0;
        for (EntityPushBounds bounds : window) {
            negativeX = Math.max(negativeX, bounds.negativeX);
            positiveX = Math.max(positiveX, bounds.positiveX);
            negativeZ = Math.max(negativeZ, bounds.negativeZ);
            positiveZ = Math.max(positiveZ, bounds.positiveZ);
        }
        return new EntityPushBounds(negativeX, positiveX, negativeZ, positiveZ);
    }

    public static final class Builder {
        private int negativeX, positiveX, negativeZ, positiveZ;

        public Builder add(int sideX, int sideZ) {
            if (sideX <= 0) negativeX++;
            if (sideX >= 0) positiveX++;
            if (sideZ <= 0) negativeZ++;
            if (sideZ >= 0) positiveZ++;
            return this;
        }

        public EntityPushBounds build() {
            return new EntityPushBounds(negativeX, positiveX, negativeZ, positiveZ);
        }
    }
}
