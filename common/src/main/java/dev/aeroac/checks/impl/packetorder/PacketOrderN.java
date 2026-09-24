package dev.aeroac.checks.impl.packetorder;

import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.BlockPlaceCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.BlockPlace;
import dev.aeroac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;

@CheckData(name = "PacketOrderN", stableKey = "grim.packetorder.place_use_order", description = "Sent use item and block place packets in an invalid order", experimental = true)
public class PacketOrderN extends BlockPlaceCheck {
    public PacketOrderN(final AeroPlayer player) {
        super(player);
    }

    private int invalid;
    private boolean usingWithoutPlacing, placing;

    @Override
    public void onBlockPlace(BlockPlace place) {
        placing = true;
        if (usingWithoutPlacing) {
            if (!player.canSkipTicks()) {
                if (flag() && shouldModifyPackets() && shouldCancel()) {
                    place.resync();
                }
            } else {
                invalid++;
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM
                || event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                && new WrapperPlayClientPlayerBlockPlacement(event).getFace() == BlockFace.OTHER) {
            if (!placing) {
                usingWithoutPlacing = true;
            }

            placing = false;
        }

        if (!player.cameraEntity.isSelf() || isTickPacket(event.getPacketType())) {
            usingWithoutPlacing = placing = false;
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (; invalid >= 1; invalid--) {
                flag();
            }
        }

        invalid = 0;
    }
}
