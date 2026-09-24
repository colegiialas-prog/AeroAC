package dev.aeroac.platform.fabric.entity;

import dev.aeroac.platform.api.entity.AeroEntity;
import dev.aeroac.platform.api.world.PlatformWorld;
import dev.aeroac.platform.fabric.inject.FabricEntityHandle;
import dev.aeroac.utils.math.Location;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

public abstract class AbstractFabricAeroEntity<T> implements AeroEntity {

    protected volatile T entity;

    public AbstractFabricAeroEntity(T entity) {
        this.entity = Objects.requireNonNull(entity);
    }

    protected void setNativeEntity(T entity) {
        this.entity = Objects.requireNonNull(entity);
    }

    protected FabricEntityHandle entityHandle() {
        return (FabricEntityHandle) entity;
    }

    @Override
    public UUID getUniqueId() {
        return entityHandle().fabricEntityUuid();
    }

    @Override
    public boolean eject() {
        return entityHandle().fabricEjectPassengers();
    }

    @Override
    public @NotNull T getNative() {
        return this.entity;
    }

    @Override
    public PlatformWorld getWorld() {
        return (PlatformWorld) entityHandle().fabricWorld();
    }

    @Override
    public Location getLocation() {
        FabricEntityHandle handle = entityHandle();
        return new Location(
                this.getWorld(),
                handle.fabricPosX(),
                handle.fabricPosY(),
                handle.fabricPosZ(),
                handle.fabricYaw(1.0F),
                handle.fabricPitch(1.0F)
        );
    }

    @Override
    public double distanceSquared(double oX, double oY, double oZ) {
        FabricEntityHandle handle = entityHandle();
        double x = handle.fabricPosX();
        double y = handle.fabricPosY();
        double z = handle.fabricPosZ();
        double distX = (x - oX) * (x - oX);
        double distY = (y - oY) * (y - oY);
        double distZ = (z - oZ) * (z - oZ);
        return distX + distY + distZ;
    }
}
