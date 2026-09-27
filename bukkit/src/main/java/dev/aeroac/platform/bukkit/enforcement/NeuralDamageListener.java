package dev.aeroac.platform.bukkit.enforcement;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.player.AeroPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Applies the neural mitigation's damage multiplier where the server actually computes a hit.
 * Melee and projectiles both count: an aim assist helps a bow as much as a sword. The listener only
 * reads the player's current, immutable mitigation action, so it is safe on any region thread.
 */
public final class NeuralDamageListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(event.getEntity().getUniqueId())) return;
        NeuralRuntime runtime = AeroAPI.INSTANCE.getNeuralManager().runtime();
        if (runtime == null) return;
        AeroPlayer player = AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(attacker.getUniqueId());
        if (player == null) return;
        double factor = runtime.damageMultiplier(player.getNeuralState(), System.nanoTime());
        if (factor < 1.0) event.setDamage(event.getDamage() * factor);
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }
}
