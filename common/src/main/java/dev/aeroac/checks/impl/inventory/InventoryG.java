package dev.aeroac.checks.impl.inventory;

import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.InventoryCheck;
import dev.aeroac.player.AeroPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;

@CheckData(name = "InventoryG", stableKey = "grim.inventory.entity_action_open", setback = 3, description = "Sent a entity action packet while inventory is open", experimental = true)
public class InventoryG extends InventoryCheck {

    public InventoryG(AeroPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.packetStateData.lastPacketWasTeleport) return;
        super.onPacketReceive(event);

        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction wrapper = new WrapperPlayClientEntityAction(event);
            WrapperPlayClientEntityAction.Action action = wrapper.getAction();

            if (action == WrapperPlayClientEntityAction.Action.STOP_SNEAKING
                    || action == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                return;
            }

            if (player.hasInventoryOpen) {
                if (flag() && !isNoSetbackPermission()) {
                    closeInventory();
                }
            } else {
                reward();
            }
        }
    }
}
