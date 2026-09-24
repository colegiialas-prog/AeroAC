package dev.aeroac.utils.item;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;

/**
 * {@code BowItem#use}: drawing starts when the player has an arrow or infinite materials.
 * <p>
 * Since 1.21 only the server takes the arrow when shooting, so the client never runs out before the server tells
 * it. When we can't be sure the client has an arrow the draw isn't tracked, which only skips checking it.
 */
public class BowItem extends ItemBehaviour {

    public static final BowItem INSTANCE = new BowItem();

    @Override
    public boolean canUse(ItemStack item, CompensatedWorld world, AeroPlayer player, InteractionHand hand) {
        return player.gamemode == GameMode.CREATIVE || player.inventory.certainlyHasArrow();
    }

}
