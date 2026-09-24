package dev.aeroac.checks.debug;

import dev.aeroac.checks.Check;
import dev.aeroac.player.AeroPlayer;

public abstract class AbstractDebugHandler extends Check {
    public AbstractDebugHandler(AeroPlayer player) {
        super(player);
    }

    public abstract boolean toggleListener(AeroPlayer player);

    public abstract boolean toggleConsoleOutput();
}
