package dev.aeroac.checks.impl.inventory;

import ac.grim.grimac.api.storage.verbose.Verbose;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.InventoryCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.data.KnownInput;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;

/**
 * Clicking in an inventory while movement keys are held: AutoTotem, AutoArmor and inventory cleaners that
 * move items without opening a screen, so the player keeps strafing through the swap. A vanilla client
 * releases every key when a screen opens and reports it before it can click (see
 * {@link MovementKeyClickGuard}). Only 1.21.2+ clients on 1.21.2+ servers report their keys.
 *
 * <p>The click is cancelled and the inventory resynced, so the swap does not happen.
 */
@CheckData(name = "InventoryH", stableKey = "aero.inventory.click_holding_movement_keys", setback = 3,
        description = "Clicked in an inventory while holding movement keys")
public class InventoryH extends InventoryCheck {
    private static final Verbose V = Verbose.of("forward={bool} backward={bool} left={bool} right={bool} jump={bool}");

    private final MovementKeyClickGuard guard = new MovementKeyClickGuard();

    public InventoryH(AeroPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        super.onPacketReceive(event);
        if (!player.supportsEndTick()) return;

        if (event.getPacketType() == PacketType.Play.Client.CLIENT_TICK_END) {
            guard.tickEnd(player.lastTransactionReceived.get());
        } else if (event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW) {
            KnownInput keys = player.packetStateData.knownInput;
            if (!guard.impossibleClick(keys.moving(), player.lastTransactionReceived.get())) {
                reward();
                return;
            }

            if (flag(V.write(verbose()).bool(keys.forward()).bool(keys.backward()).bool(keys.left())
                    .bool(keys.right()).bool(keys.jump())) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
                player.inventory.needResend = true;
            }
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.OPEN_WINDOW
                || event.getPacketType() == PacketType.Play.Server.OPEN_HORSE_WINDOW) {
            // The window listener sends a transaction of its own around this packet; skipping one more
            // guarantees the confirming transaction is written after the open packet
            guard.serverOpenedScreen(player.lastTransactionSent.get() + 2);
        }
    }
}
