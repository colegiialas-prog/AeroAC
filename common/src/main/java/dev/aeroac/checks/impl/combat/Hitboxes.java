package dev.aeroac.checks.impl.combat;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.player.AeroPlayer;

@CheckData(name = "Hitboxes", stableKey = "grim.combat.hitboxes", description = "Tried to hit an entity outside its valid hitbox")
public class Hitboxes extends Check {
    public Hitboxes(AeroPlayer player) {
        super(player);
    }
}
