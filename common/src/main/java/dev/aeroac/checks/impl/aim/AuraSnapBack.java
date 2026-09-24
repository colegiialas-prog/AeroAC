package dev.aeroac.checks.impl.aim;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.RotationCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.RotationUpdate;

/**
 * Snap auras: the head jumps onto the target for the hit and straight back to the view afterwards.
 * The pattern itself is described and judged by {@link SnapBackDetector}.
 */
@CheckData(name = "AuraSnapBack", stableKey = "aero.aim.snap_back", decay = 0.01,
        description = "Snapped onto targets for hits and straight back")
public class AuraSnapBack extends Check implements RotationCheck {
    private final SnapBackDetector detector = new SnapBackDetector();

    public AuraSnapBack(AeroPlayer player) {
        super(player);
    }

    /** Called by the combat tracker the moment an attack arrives. */
    public void onAttack() {
        if (detector.attack(System.currentTimeMillis())) flag();
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        // Teleports, vehicle switches and horse interactions turn the head without the player
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation) {
            detector.discontinuity();
        }
        if (detector.rotation(player.yaw, player.pitch, System.currentTimeMillis())) flag();
    }
}
