package dev.aeroac.platform.fabric.mc1194.entity;

import dev.aeroac.platform.fabric.mc1171.entity.Fabric1170AeroEntity;
import dev.aeroac.platform.fabric.utils.thread.FabricFutureUtil;
import dev.aeroac.utils.math.Location;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;

public class Fabric1194AeroEntity extends Fabric1170AeroEntity {

    public Fabric1194AeroEntity(Entity entity) {
        super(entity);
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Location location) {
        return FabricFutureUtil.supplySync(() -> {
            if (entity.getLevel() instanceof ServerLevel) {
                entity.teleportTo(
                        (ServerLevel) location.getWorld(),
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        EnumSet.noneOf(RelativeMovement.class), // todo change to match paper? Do they do this?
                        location.getYaw(),
                        location.getPitch()

                );
                return true;
            }
            return false;
        });
    }
}
