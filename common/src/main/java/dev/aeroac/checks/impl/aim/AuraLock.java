package dev.aeroac.checks.impl.aim;

import ac.grim.grimac.api.storage.verbose.Verbose;
import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.impl.aim.processor.AimProcessor;
import dev.aeroac.checks.impl.aim.processor.CombatTargetTracker;
import dev.aeroac.checks.type.RotationCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.RotationUpdate;
import dev.aeroac.utils.collisions.datatypes.SimpleCollisionBox;
import dev.aeroac.utils.data.packetentity.PacketEntity;

/**
 * Auras that lock the head onto the middle of the target: every sample where the attacked target moved
 * across the view, the yaw is compared with the bearing of its centre. The windows are counted by
 * {@link CenterLockTracker}.
 *
 * <p>Timing: rotation checks run before the movement packet ticks the entities, so the entity model is
 * as of the client's previous tick, and so is the eye ({@code player.x} is the last reported position).
 * An aura computes its angle at the start of its tick, when the target may or may not have been ticked
 * yet; both positions are accepted. That is why a sample is judged one packet later, once the next
 * position of the target is known too.
 */
@CheckData(name = "AuraLock", stableKey = "aero.aim.lock", decay = 0.01,
        description = "Aim stayed on the exact centre of a moving target")
public class AuraLock extends Check implements RotationCheck {
    private static final Verbose V = Verbose.of("tolerance={f64}");

    /** Rotation samples after an attack that still count as combat. */
    private static final int COMBAT_SAMPLES = 40;
    private static final double MIN_DISTANCE = 1.5;
    private static final double MAX_DISTANCE = 6.0;
    /** Only judge when the target's centre is known to within this many degrees of yaw. */
    private static final double MAX_UNCERTAINTY = 1.5;
    /** Only judge when the centre moved at least this far across the view in one tick. */
    private static final double MIN_MOTION = 1.5;
    /** Mouse step assumed until the sensitivity is known. */
    private static final double DEFAULT_MOUSE_STEP = 0.6;
    /** Past this mouse step no yaw is precise enough to tell a lock from a good hand. */
    private static final double MAX_MOUSE_STEP = 0.8;

    private final CenterLockTracker tracker = new CenterLockTracker();
    private int lastAttacks;
    private int samplesSinceAttack = COMBAT_SAMPLES + 1;

    private boolean pending;
    private PacketEntity pendingTarget;
    private int pendingFlyings;
    private double pendingEyeX, pendingEyeZ;
    private float pendingYaw;
    private double pendingLow, pendingHigh;
    private double pendingTolerance;

    public AuraLock(AeroPlayer player) {
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

        PacketEntity target = combat.target();
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation || player.inVehicle()
                || samplesSinceAttack > COMBAT_SAMPLES || target == null || !target.isLivingEntity
                || target.isDead || target.riding != null || target.presenceUncertain) {
            pending = false;
            return;
        }

        SimpleCollisionBox location = target.getPossibleLocationBoxes();
        if (pending && target == pendingTarget && combat.flyings() == pendingFlyings + 1) judgePending(location);
        remember(rotationUpdate, target, combat, location);
    }

    private void judgePending(SimpleCollisionBox location) {
        // Where the target is now, seen from where the eye was when the pending yaw was chosen
        double[] next = CenterLockTracker.centerOffsets(pendingEyeX, pendingEyeZ,
                location.minX, location.minZ, location.maxX, location.maxZ, pendingYaw);
        if (next == null) return;
        double motion = Math.abs(CenterLockTracker.wrap((next[0] + next[1]) * 0.5 - (pendingLow + pendingHigh) * 0.5));
        if (motion < MIN_MOTION) return;

        double tolerance = pendingTolerance;
        boolean onBefore = pendingLow - tolerance <= 0 && pendingHigh + tolerance >= 0;
        boolean onAfter = next[1] - next[0] <= MAX_UNCERTAINTY && next[0] - tolerance <= 0 && next[1] + tolerance >= 0;
        if (tracker.sample(onBefore || onAfter)) flag(V.write(verbose()).f64(tolerance));
    }

    private void remember(RotationUpdate rotationUpdate, PacketEntity target, CombatTargetTracker combat, SimpleCollisionBox location) {
        pending = false;
        // An eye hidden by the 0.03 threshold is not known precisely enough
        if (player.uncertaintyHandler.lastMovementWasZeroPointZeroThree) return;

        AimProcessor processor = rotationUpdate.getProcessor();
        double step = processor != null && processor.modeX > 0 ? processor.modeX : DEFAULT_MOUSE_STEP;
        if (step > MAX_MOUSE_STEP) return;

        double centerX = (location.minX + location.maxX) * 0.5 - player.x;
        double centerZ = (location.minZ + location.maxZ) * 0.5 - player.z;
        double distance = Math.sqrt(centerX * centerX + centerZ * centerZ);
        if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) return;

        double[] offsets = CenterLockTracker.centerOffsets(player.x, player.z,
                location.minX, location.minZ, location.maxX, location.maxZ, player.yaw);
        if (offsets == null || offsets[1] - offsets[0] > MAX_UNCERTAINTY) return;

        pending = true;
        pendingTarget = target;
        pendingFlyings = combat.flyings();
        pendingEyeX = player.x;
        pendingEyeZ = player.z;
        pendingYaw = player.yaw;
        pendingLow = offsets[0];
        pendingHigh = offsets[1];
        // Rounding the aim to whole mouse steps, twice at worst, plus a little deliberate tremor
        pendingTolerance = 0.3 + 1.5 * step;
    }
}
