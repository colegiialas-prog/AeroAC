package dev.aeroac.checks.impl.baritone;

import ac.grim.grimac.api.storage.verbose.Verbose;
import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.impl.aim.processor.AimProcessor;
import dev.aeroac.checks.type.RotationCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.RotationUpdate;
import dev.aeroac.utils.data.HeadRotation;
import dev.aeroac.utils.math.AeroMath;

// This check has been patched by Baritone for a long time, and it also seems to false with cinematic camera now, so it is disabled.
@CheckData(name = "Baritone", stableKey = "grim.baritone.baritone", description = "Detected Baritone like behavior")
public class Baritone extends Check implements RotationCheck {
    private static final Verbose V = Verbose.of("divisor={f64}");

    private int verbose;

    public Baritone(AeroPlayer playerData) {
        super(playerData);
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        final HeadRotation from = rotationUpdate.getFrom();
        final HeadRotation to = rotationUpdate.getTo();

        final float deltaPitch = Math.abs(to.pitch() - from.pitch());

        // Baritone works with small degrees, limit to 1 degree to pick up on baritone slightly moving aim to bypass anticheats
        if (rotationUpdate.getDeltaXRot() == 0 && deltaPitch > 0 && deltaPitch < 1 && Math.abs(to.pitch()) != 90.0f) {
            if (rotationUpdate.getProcessor().divisorY < AeroMath.MINIMUM_DIVISOR) {
                verbose++;
                if (verbose > 8) {
                    double divisor = AimProcessor.convertToSensitivity(rotationUpdate.getProcessor().divisorX);
                    flag(V.write(verbose()).f64(divisor));
                }
            } else {
                verbose = 0;
            }
        }
    }
}
