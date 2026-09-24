package dev.aeroac.platform.fabric.mc1171.entity;

import dev.aeroac.platform.fabric.mc1161.entity.Fabric1161AeroEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class Fabric1170AeroEntity extends Fabric1161AeroEntity {

    public Fabric1170AeroEntity(Entity entity) {
        super(entity);
    }

    @Override
    public boolean isDead() {
        return this.entity instanceof LivingEntity living ? living.isDeadOrDying() : this.entity.isRemoved();
    }
}
