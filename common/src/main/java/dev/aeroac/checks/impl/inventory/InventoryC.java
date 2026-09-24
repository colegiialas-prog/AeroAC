package dev.aeroac.checks.impl.inventory;

import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.InventoryCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.BlockPlace;

@CheckData(name = "InventoryC", stableKey = "grim.inventory.place_open", setback = 3, description = "Placed a block while inventory is open")
public class InventoryC extends InventoryCheck {

    public InventoryC(AeroPlayer player) {
        super(player);
    }

    public void onBlockPlace(final BlockPlace place) {
        // It is not possible to place a block while the inventory is open
        if (player.hasInventoryOpen) {
            if (flag()) {
                if (shouldModifyPackets()) {
                    place.resync();
                }
                if (!isNoSetbackPermission()) {
                    closeInventory();
                }
            }
        } else {
            reward();
        }
    }
}
