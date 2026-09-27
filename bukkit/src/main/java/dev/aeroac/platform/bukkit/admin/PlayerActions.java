package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.locale.AeroMessages;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an operator does to a suspect from the profile screen: go there, watch as a spectator,
 * freeze. Kick, ban and clearing the risk are confirmations built in ProfileMenu; this class holds
 * the state that has to outlive a screen and undoes it when someone leaves.
 *
 * <p>Teleports are asynchronous (Paper/Folia); everything else runs on the thread of the click.
 */
public final class PlayerActions implements Listener {
    /** Where a spectating operator came from, so leaving spectate puts them back exactly. */
    private record Return(GameMode mode, Location location, UUID target) { }

    private final Map<UUID, Return> spectating = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    public boolean spectating(UUID operator) { return spectating.containsKey(operator); }
    public boolean frozen(UUID player) { return frozen.contains(player); }

    public void teleport(Player operator, Player target) {
        operator.teleportAsync(target.getLocation());
        operator.sendMessage(MenuItems.GOOD + AeroMessages.tr("gui.action.teleported", target.getName()));
    }

    /** Spectator mode attached to the target's camera; a second call restores the operator. */
    public void toggleSpectate(Player operator, Player target) {
        Return back = spectating.remove(operator.getUniqueId());
        if (back != null) {
            restore(operator, back);
            return;
        }
        spectating.put(operator.getUniqueId(), new Return(operator.getGameMode(), operator.getLocation(), target.getUniqueId()));
        operator.setGameMode(GameMode.SPECTATOR);
        operator.teleportAsync(target.getLocation()).thenRun(() -> {
            if (operator.isOnline() && target.isOnline() && spectating.containsKey(operator.getUniqueId())) {
                operator.setSpectatorTarget(target);
            }
        });
        operator.sendMessage(MenuItems.GOOD + AeroMessages.tr("gui.action.spectating", target.getName()));
    }

    private void restore(Player operator, Return back) {
        operator.setSpectatorTarget(null);
        operator.setGameMode(back.mode());
        operator.teleportAsync(back.location());
        operator.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.action.spectate_ended"));
    }

    public boolean toggleFreeze(Player operator, Player target) {
        boolean now = frozen.add(target.getUniqueId());
        if (!now) frozen.remove(target.getUniqueId());
        target.sendMessage((now ? MenuItems.BAD : MenuItems.GOOD)
                + AeroMessages.tr(now ? "gui.action.you_are_frozen" : "gui.action.you_are_unfrozen"));
        operator.sendMessage(MenuItems.HEADER + AeroMessages.tr(now ? "gui.action.frozen" : "gui.action.unfrozen", target.getName()));
        return now;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        Location from = event.getFrom(), to = event.getTo();
        // Looking around stays allowed: an operator watching a frozen suspect wants to see them aim.
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location keep = from.clone();
            keep.setYaw(to.getYaw());
            keep.setPitch(to.getPitch());
            event.setTo(keep);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && frozen.contains(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Return back = spectating.remove(id);
        if (back != null) event.getPlayer().setGameMode(back.mode());
        // Whoever was watching the player who left gets their own view back.
        spectating.forEach((operator, entry) -> {
            if (!entry.target().equals(id)) return;
            Player watcher = org.bukkit.Bukkit.getPlayer(operator);
            if (watcher != null && spectating.remove(operator) != null) restore(watcher, entry);
        });
        // A frozen player stays frozen across a relog: logging out is not a way out of a check.
    }

    /** Plugin disable: nobody is left in spectator mode or frozen by a screen that no longer exists. */
    public void releaseAll() {
        spectating.forEach((operator, back) -> {
            Player player = org.bukkit.Bukkit.getPlayer(operator);
            if (player != null) player.setGameMode(back.mode());
        });
        spectating.clear();
        frozen.clear();
    }
}
