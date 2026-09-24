package dev.aeroac.checks.impl.badpackets;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.player.AeroPlayer;

@CheckData(name = "BadPacketsN", stableKey = "grim.badpackets.invalid_teleport", description = "Ignored or failed to accept a required server teleport", setback = 0)
public class BadPacketsN extends Check {
    public BadPacketsN(final AeroPlayer player) {
        super(player);
    }
}
