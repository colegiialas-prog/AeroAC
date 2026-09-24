package dev.aeroac.utils.item;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;

public class AlwaysUseItem extends ItemBehaviour {

    public static final AlwaysUseItem INSTANCE = new AlwaysUseItem();

    @Override
    public boolean canUse(ItemStack item, CompensatedWorld world, AeroPlayer player, InteractionHand hand) {
        return true;
    }

}
