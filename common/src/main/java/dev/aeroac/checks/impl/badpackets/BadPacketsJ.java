package dev.aeroac.checks.impl.badpackets;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.PacketCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.data.HeadRotation;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;

import java.util.ArrayList;
import java.util.List;

@CheckData(name = "BadPacketsJ", stableKey = "grim.badpackets.use_item_rotation_mismatch", description = "Rotation in use item packet did not match tick rotation")
public class BadPacketsJ extends Check implements PacketCheck {
    private final List<HeadRotation> rotations = new ArrayList<>();

    public BadPacketsJ(AeroPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf()) {
            rotations.clear();
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21)
                && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21)) {
            WrapperPlayClientUseItem packet = new WrapperPlayClientUseItem(event);
            rotations.add(new HeadRotation(packet.getYaw(), packet.getPitch()));
        }

        if (isTickPacket(event.getPacketType())) {
            // due to tick skipping, the rotations sent could be last tick's
            boolean allowLast = player.canSkipTicks() && (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION);
            for (HeadRotation rotation : rotations) {
                if (rotation.yaw() == player.yaw && rotation.pitch() == player.pitch) {
                    allowLast = false;
                    continue;
                }

                if (rotation.yaw() == player.lastYaw && rotation.pitch() == player.lastPitch && allowLast) {
                    continue;
                }

                flag();
            }

            rotations.clear();
        }
    }
}
