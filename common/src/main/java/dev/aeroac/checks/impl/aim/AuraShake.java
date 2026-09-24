package dev.aeroac.checks.impl.aim;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.impl.aim.processor.CombatTargetTracker;
import dev.aeroac.checks.type.RotationCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.RotationUpdate;

/**
 * Auras that shake the head around the target to break aim models. Only judged in combat, where a
 * shaking head has a reason to be an aura; the pattern is described and judged by {@link ShakeDetector}.
 */
@CheckData(name = "AuraShake", stableKey = "aero.aim.shake", decay = 0.01,
        description = "Head shook around the target in combat")
public class AuraShake extends Check implements RotationCheck {
    /** Rotation samples after an attack that still count as combat. */
    private static final int COMBAT_SAMPLES = 60;

    private final ShakeDetector detector = new ShakeDetector();
    private int lastAttacks;
    private int samplesSinceAttack = COMBAT_SAMPLES + 1;
    /** Yaw accumulated from wrapped steps, so a client that folds its yaw does not look like a reversal. */
    private double yaw;

    public AuraShake(AeroPlayer player) {
        super(player);
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        CombatTargetTracker combat = player.checkManager.getPacketCheck(CombatTargetTracker.class);
        if (combat.attacks() != lastAttacks) {
            lastAttacks = combat.attacks();
            samplesSinceAttack = 0;
        } else if (samplesSinceAttack <= COMBAT_SAMPLES) {
            samplesSinceAttack++;
        }

        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation || samplesSinceAttack > COMBAT_SAMPLES) {
            detector.reset();
            yaw = 0;
            return;
        }

        yaw += CenterLockTracker.wrap(rotationUpdate.getDeltaXRot());
        if (detector.rotation(yaw, player.pitch)) flag();
    }
}
