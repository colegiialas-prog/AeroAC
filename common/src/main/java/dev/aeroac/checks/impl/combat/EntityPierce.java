package dev.aeroac.checks.impl.combat;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.PacketCheck;
import dev.aeroac.player.AeroPlayer;

@CheckData(name = "EntityPierce", stableKey = "grim.combat.entity_pierce", configName = "EntityPierce", setback = 30)
public class EntityPierce extends Check implements PacketCheck {
    public EntityPierce(AeroPlayer player) {
        super(player);
    }
}
