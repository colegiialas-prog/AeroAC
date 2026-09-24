package dev.aeroac.utils.item;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;

public class UnsupportedItem extends ItemBehaviour {

    public static final UnsupportedItem INSTANCE = new UnsupportedItem();

    @Override
    public boolean canUse(ItemStack item, CompensatedWorld world, AeroPlayer player, InteractionHand hand) {
        return false;
    }

}
