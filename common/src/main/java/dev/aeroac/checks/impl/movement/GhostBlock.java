package dev.aeroac.checks.impl.movement;

import ac.grim.grimac.api.storage.verbose.Verbose;
import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.PostPredictionCheck;
import dev.aeroac.manager.SetbackTeleportUtil;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.PredictionComplete;
import dev.aeroac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.util.Vector3d;

/**
 * Movement that used a block, gap or fluid the server then refused.
 *
 * <p>Since 1.19 the client places, breaks and pours immediately and waits for the server to confirm. Until
 * the answer arrives the client really has that world, so its movement is simulated with it. Where the
 * server refuses - a protected region, a claim, a rollback - the client could already have jumped off
 * the block, swum up the water or walked through the broken wall. Spider modules do exactly that in
 * regions: place a block or pour water they are not allowed to, climb it before the refusal arrives, and
 * repeat. Holding back the refusal only lengthens how long the ghost stays usable.
 *
 * <p>The world model records, while a prediction is pending, the position before the player's movement
 * first relied on it ({@link dev.aeroac.utils.latency.CompensatedWorld#markPredictionsUsed}). When the
 * server refuses it, the player is set back to that position: everything gained with the ghost is undone.
 * A player who placed a block they may not place but never stood on it is left alone.
 */
@CheckData(name = "GhostBlock", stableKey = "aero.world.ghost_block", decay = 0.02,
        description = "Moved using a block or fluid the server refused")
public class GhostBlock extends Check implements PostPredictionCheck {
    private static final Verbose V = Verbose.of("gained={f64}");

    public GhostBlock(AeroPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked() || player.inVehicle()) return;
        player.compensatedWorld.markPredictionsUsed(player.boundingBox, player.onGround, player.horizontalCollision,
                new Vector3d(player.lastX, player.lastY, player.lastZ));
    }

    /** The server refused a prediction the player's movement relied on, first from this position. */
    public void onRefusedPredictionUsed(Vector3d positionBeforeUse) {
        // Every later use happened on top of this one; the setback undoes them all
        player.compensatedWorld.forgetPredictionUse();
        flag(V.write(verbose()).f64(player.y - positionBeforeUse.getY()));

        SetbackTeleportUtil setback = player.getSetbackTeleportUtil();
        setback.lastKnownGoodPosition = new SetbackTeleportUtil.SetbackPosWithVector(positionBeforeUse, new Vector3dm());
        setback.executeViolationSetback();
    }
}
