package dev.aeroac.checks.impl.combat;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.PacketCheck;
import dev.aeroac.player.AeroPlayer;

@CheckData(name = "WallHit", stableKey = "grim.combat.wall_hit", configName = "WallHit", setback = 20)
public class WallHit extends Check implements PacketCheck {
    public WallHit(AeroPlayer player) {
        super(player);
    }
}
