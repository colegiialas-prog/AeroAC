package dev.aeroac.checks.impl.movement;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.PacketCheck;
import dev.aeroac.player.AeroPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;

/**
 * A client that keeps ticking but stops reporting where it is.
 *
 * <p>The prediction engine only runs when a position arrives, so a client that simply stops sending
 * positions is never simulated at all: it hangs in the air where it last reported, and its attacks are
 * checked against that frozen spot. That is the whole of "air stuck" — cancel every movement packet,
 * keep sending attacks.
 *
 * <p>1.21.2+ clients end every tick with {@code CLIENT_TICK_END}, and a vanilla client that is not
 * riding anything sends its position at least every 20 ticks even when it has not moved. So more than
 * 20 tick ends in a row with no position is not lag and not standing still: it is a client that is
 * ticking and withholding its position (see {@link PositionReminder}). The flag sets the player back,
 * and a pending setback cancels their attacks until the client accepts it with a real position — the
 * very packet the cheat refuses to send.
 *
 * <p>Older clients have no tick-end packet, and without it a frozen client cannot be told apart from
 * a lagging one, so they are not checked here.
 */
@CheckData(name = "AirStuck", stableKey = "aero.movement.air_stuck",
        description = "Kept ticking without reporting its position", setback = 0)
public class AirStuck extends Check implements PacketCheck {
    private static final Verbose V = Verbose.of("ticks={uint}");

    private PositionReminder reminder = new PositionReminder(25);
    /** The server put the client's camera on another entity; a vanilla client then sends no position. */
    private boolean cameraOnOtherEntity;

    public AirStuck(AeroPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!player.supportsEndTick()) return;

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            // Counted whatever happens to the packet afterwards: the client did report a position.
            if (new WrapperPlayClientPlayerFlying(event).hasPositionChanged()) reminder.position();
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.CLIENT_TICK_END) {
            int ticks = reminder.tickEnd(exempt());
            if (ticks > 0) flagWithSetback(V.write(verbose()).uint(ticks));
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // A new world or a respawn: the client does not tick its player until it has loaded, and it
        // reports the first position when it has. Nothing may be counted before that.
        if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME
                || event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            cameraOnOtherEntity = false;
            reminder.disarm();
        } else if (event.getPacketType() == PacketType.Play.Server.CAMERA) {
            // Cutscene and spectate plugins move the camera; LocalPlayer only reports its position while
            // it is the camera. Disarming also covers the latency before the client sees the change back.
            cameraOnOtherEntity = new WrapperPlayServerCamera(event).getCameraId() != player.entityID;
            reminder.disarm();
        }
    }

    /** States in which a vanilla client legitimately sends no position. */
    private boolean exempt() {
        return player.inVehicle()                                    // passengers send vehicle moves instead
                || player.compensatedEntities.self.isDead            // death screen
                || player.isInBed
                || player.gamemode == GameMode.SPECTATOR             // camera may be on another entity
                || cameraOnOtherEntity
                || player.getSetbackTeleportUtil().shouldBlockMovement(); // unloaded chunk or pending setback
    }

    @Override
    public void onReload(ConfigManager config) {
        // Never below what vanilla itself can produce; PositionReminder enforces the floor.
        reminder = new PositionReminder(config.getIntElse(getConfigName() + ".max-ticks", 25));
    }
}
