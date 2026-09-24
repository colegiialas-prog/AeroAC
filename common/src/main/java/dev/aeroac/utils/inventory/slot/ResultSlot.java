package dev.aeroac.utils.inventory.slot;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.inventory.InventoryStorage;
import com.github.retrooper.packetevents.protocol.item.ItemStack;

public class ResultSlot extends Slot {

    public ResultSlot(InventoryStorage container, int slot) {
        super(container, slot);
    }

    @Override
    public boolean mayPlace(ItemStack itemStack) {
        return false;
    }

    @Override
    public void onTake(AeroPlayer player, ItemStack itemStack) {
        // Resync the player's inventory
    }
}
