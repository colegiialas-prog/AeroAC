package dev.aeroac.platform.bukkit.enforcement;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * For the few seconds of the send-off, the player does nothing at all.
 *
 * <p>"Nothing" is enumerated rather than assumed: walking, chatting, running a command, opening a
 * container, clicking in one, dropping an item, picking one up, swapping hands, placing or breaking
 * a block, interacting with anything, hitting somebody, being hit, and being teleported away by
 * another plugin. Anything not on this list still works, so the list is the specification.
 *
 * <p>Vertical movement is deliberately left alone — that is the levitation carrying them up, and
 * cancelling it would pin them to the ground and there would be no send-off. Looking around is
 * left alone too: they should be able to watch.
 */
public final class FreezeListener implements Listener {
    private final BukkitBanPresenter presenter;

    public FreezeListener(BukkitBanPresenter presenter) {
        this.presenter = presenter;
    }

    private boolean frozen(Player player) {
        return player != null && presenter.frozen(player.getUniqueId());
    }

    /** Horizontal position is pinned to where they were standing; the rise is left to levitation. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen(event.getPlayer())) return;
        Location anchor = presenter.anchor(event.getPlayer().getUniqueId());
        Location to = event.getTo();
        if (anchor == null || to == null) return;
        if (to.getX() == anchor.getX() && to.getZ() == anchor.getZ()) return;
        event.setTo(new Location(to.getWorld(), anchor.getX(), to.getY(), anchor.getZ(),
                to.getYaw(), to.getPitch()));
    }

    /** Another plugin's teleport would end the show early and drop them somewhere unexpected. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    /**
     * Chat is refused too: "cannot type anything" includes the last word.
     *
     * <p>The legacy event, deliberately. It fires on Spigot and on Paper alike, and cancelling it on
     * Paper still cancels the message, so one handler covers every server this plugin runs on.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    /** Their own scattered gear flies past them on the way up; they do not get to catch it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && frozen(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && frozen(player)) event.setCancelled(true);
    }

    /** Items leave through the animation, not through the player throwing them clear. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    /** They cannot be hurt, and cannot hurt anybody, while they are held in the air. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && frozen(player)) event.setCancelled(true);
    }

    /**
     * Leaving mid-flight ends the show and runs the ban anyway.
     *
     * <p>Logging out during the countdown is the obvious way to try to escape one, and it is also
     * what happens when somebody's connection simply drops. Both get the same treatment, because
     * the decision was made before any of this started.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        presenter.release(event.getPlayer().getUniqueId());
    }
}
