package dev.aeroac.checks.impl.badpackets;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.player.AeroPlayer;

@CheckData(name = "BadPacketsW", stableKey = "grim.badpackets.invalid_entity_target", description = "Interacted with non-existent entity", experimental = true)
public class BadPacketsW extends Check {
    public BadPacketsW(AeroPlayer player) {
        super(player);
    }
}
