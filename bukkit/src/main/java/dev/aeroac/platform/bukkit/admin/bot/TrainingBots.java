package dev.aeroac.platform.bukkit.admin.bot;

import dev.aeroac.locale.AeroMessages;
import dev.aeroac.platform.bukkit.AeroACBukkitLoaderPlugin;
import dev.aeroac.platform.bukkit.admin.MenuItems;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attributable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sparring bots for collecting training data: one zombie per operator (same 0.6 x 1.95 box as a
 * player, so the recorded target geometry is a player's), steered by hand every tick instead of by
 * vanilla AI, so it stands, follows, strafes, runs or fights back exactly as the menu says.
 *
 * <p>Bots never persist into the world: they are not saved with the chunk and are removed when
 * their owner leaves or the plugin stops. Each bot runs on its own entity scheduler (Folia safe).
 */
public final class TrainingBots implements Listener {
    /** Armor sets and weapons offered by the menu; names that do not exist on this version are skipped. */
    public static final String[] ARMOR = {"NONE", "LEATHER", "CHAINMAIL", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"};
    public static final String[] WEAPONS = {"NONE", "WOODEN_SWORD", "STONE_SWORD", "IRON_SWORD", "DIAMOND_SWORD",
            "NETHERITE_SWORD", "IRON_AXE", "DIAMOND_AXE", "SHIELD"};

    private static final class Bot {
        final UUID owner;
        final BotSettings settings;
        volatile Zombie entity;
        volatile ScheduledTask task;
        int grace, cooldown, wanderTicks;
        double strafe = 1, wanderX, wanderZ;

        Bot(UUID owner, BotSettings settings) { this.owner = owner; this.settings = settings; }
    }

    private final Map<UUID, Bot> bots = new ConcurrentHashMap<>();
    private final Map<UUID, BotSettings> settings = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> ownerOfEntity = new ConcurrentHashMap<>();

    public BotSettings settings(UUID owner) { return settings.computeIfAbsent(owner, id -> new BotSettings()); }

    public boolean active(UUID owner) {
        Bot bot = bots.get(owner);
        return bot != null && bot.entity != null && bot.entity.isValid();
    }

    public int count() { return bots.size(); }

    /** Spawns the operator's bot a few blocks in front of them, replacing an existing one. */
    public void spawn(Player owner) {
        remove(owner.getUniqueId());
        Location eye = owner.getLocation();
        Vector ahead = eye.getDirection().setY(0);
        if (ahead.lengthSquared() < 1e-6) ahead = new Vector(0, 0, 1);
        Location at = eye.clone().add(ahead.normalize().multiply(3));
        at.setY(owner.getLocation().getY());
        Bot bot = new Bot(owner.getUniqueId(), settings(owner.getUniqueId()));
        Zombie zombie = owner.getWorld().spawn(at, Zombie.class);
        zombie.setPersistent(false);
        zombie.setRemoveWhenFarAway(false);
        zombie.setCanPickupItems(false);
        zombie.setAdult();
        zombie.setCustomName(MenuItems.WARN + "Aero Bot " + MenuItems.MUTED + "(" + owner.getName() + ")");
        zombie.setCustomNameVisible(true);
        try {
            zombie.setAware(false); // goals off, physics and knockback on: we steer it ourselves
        } catch (NoSuchMethodError legacy) {
            zombie.setAI(false);
        }
        bot.entity = zombie;
        ownerOfEntity.put(zombie.getUniqueId(), owner.getUniqueId());
        bots.put(owner.getUniqueId(), bot);
        apply(owner.getUniqueId());
        bot.task = zombie.getScheduler().runAtFixedRate(AeroACBukkitLoaderPlugin.LOADER, task -> tick(bot), () -> forget(bot), 1L, 1L);
        owner.sendMessage(MenuItems.GOOD + AeroMessages.tr("gui.bot.spawned"));
    }

    /** Re-applies equipment and health after a menu change. */
    public void apply(UUID owner) {
        Bot bot = bots.get(owner);
        if (bot == null || bot.entity == null) return;
        Zombie zombie = bot.entity;
        zombie.getScheduler().run(AeroACBukkitLoaderPlugin.LOADER, task -> {
            EntityEquipment equipment = zombie.getEquipment();
            if (equipment != null) {
                String set = ARMOR[bot.settings.armor];
                equipment.setHelmet(piece(set, "HELMET"));
                equipment.setChestplate(piece(set, "CHESTPLATE"));
                equipment.setLeggings(piece(set, "LEGGINGS"));
                equipment.setBoots(piece(set, "BOOTS"));
                Material weapon = Material.matchMaterial(WEAPONS[bot.settings.weapon]);
                equipment.setItemInMainHand(weapon == null ? null : new ItemStack(weapon));
                equipment.setHelmetDropChance(0); equipment.setChestplateDropChance(0);
                equipment.setLeggingsDropChance(0); equipment.setBootsDropChance(0);
                equipment.setItemInMainHandDropChance(0);
            }
            setMaxHealth(zombie, bot.settings.healthValue());
        }, null);
    }

    private static ItemStack piece(String set, String slot) {
        if ("NONE".equals(set)) return null;
        Material material = Material.matchMaterial(set + "_" + slot);
        return material == null ? null : new ItemStack(material);
    }

    @SuppressWarnings("deprecation")
    private static void setMaxHealth(Zombie zombie, double value) {
        try {
            var attribute = ((Attributable) zombie).getAttribute(org.bukkit.Registry.ATTRIBUTE.get(org.bukkit.NamespacedKey.minecraft("max_health")));
            if (attribute == null) attribute = ((Attributable) zombie).getAttribute(org.bukkit.Registry.ATTRIBUTE.get(org.bukkit.NamespacedKey.minecraft("generic.max_health")));
            if (attribute != null) attribute.setBaseValue(value);
        } catch (RuntimeException | LinkageError unsupported) {
            zombie.setMaxHealth(value);
        }
        zombie.setHealth(Math.min(value, zombie.getMaxHealth()));
    }

    public void teleportToOwner(Player owner) {
        Bot bot = bots.get(owner.getUniqueId());
        if (bot == null || bot.entity == null) return;
        bot.entity.teleportAsync(owner.getLocation());
    }

    public void remove(UUID owner) {
        Bot bot = bots.remove(owner);
        if (bot == null) return;
        if (bot.task != null) bot.task.cancel();
        Zombie zombie = bot.entity;
        if (zombie != null) {
            ownerOfEntity.remove(zombie.getUniqueId());
            zombie.getScheduler().run(AeroACBukkitLoaderPlugin.LOADER, task -> zombie.remove(), null);
        }
    }

    public void removeAll() {
        for (UUID owner : bots.keySet()) remove(owner);
    }

    private void forget(Bot bot) {
        bots.remove(bot.owner, bot);
        if (bot.entity != null) ownerOfEntity.remove(bot.entity.getUniqueId());
    }

    /** One tick of steering, on the bot's own region thread. */
    private void tick(Bot bot) {
        Zombie zombie = bot.entity;
        Player owner = org.bukkit.Bukkit.getPlayer(bot.owner);
        if (zombie == null || !zombie.isValid()) { remove(bot.owner); return; }
        if (owner == null || !owner.isValid() || owner.getWorld() != zombie.getWorld()) return;
        BotSettings s = bot.settings;
        Location me = zombie.getLocation(), them = owner.getLocation();
        double dx = them.getX() - me.getX(), dz = them.getZ() - me.getZ();
        double dist = Math.max(1e-6, Math.hypot(dx, dz));
        // Always look at the owner, like a player tracking an opponent.
        double dy = owner.getEyeLocation().getY() - zombie.getEyeLocation().getY();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        zombie.setRotation(yaw, pitch);

        if (bot.cooldown > 0) bot.cooldown--;
        if (bot.grace > 0) { bot.grace--; return; } // let knockback play out
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double ux = dx / dist, uz = dz / dist, speed = s.speedValue(), keep = s.distanceValue();
        double vx = 0, vz = 0;
        switch (s.mode) {
            case STAND -> { }
            case FOLLOW -> {
                if (dist > keep) { vx = ux * speed; vz = uz * speed; }
            }
            case STRAFE, FIGHT -> {
                double want = s.mode == BotSettings.Mode.FIGHT ? Math.min(keep, 2.2) : keep;
                if (random.nextInt(50) == 0) bot.strafe = -bot.strafe;
                double radial = Math.max(-1, Math.min(1, (dist - want) * 0.6));
                vx = (-uz * bot.strafe * 0.8 + ux * radial) * speed;
                vz = (ux * bot.strafe * 0.8 + uz * radial) * speed;
            }
            case RUN -> {
                if (--bot.wanderTicks <= 0 || dist > 10) {
                    double angle = dist > 10 ? Math.atan2(uz, ux) + random.nextDouble(-0.6, 0.6) : random.nextDouble(0, Math.PI * 2);
                    bot.wanderX = Math.cos(angle);
                    bot.wanderZ = Math.sin(angle);
                    bot.wanderTicks = random.nextInt(15, 45);
                }
                vx = bot.wanderX * speed;
                vz = bot.wanderZ * speed;
            }
        }
        Vector velocity = zombie.getVelocity();
        boolean ground = zombie.isOnGround();
        if (ground) {
            velocity.setX(vx).setZ(vz);
            boolean blocked = (vx != 0 || vz != 0) && me.clone().add(Math.signum(vx) * 0.6, 0.2, Math.signum(vz) * 0.6).getBlock().getType().isSolid();
            if (blocked || (s.jumping && random.nextInt(25) == 0)) velocity.setY(0.42);
        } else {
            velocity.setX(velocity.getX() * 0.9 + vx * 0.1).setZ(velocity.getZ() * 0.9 + vz * 0.1);
        }
        zombie.setVelocity(velocity);

        if (s.mode == BotSettings.Mode.FIGHT && s.damageValue() > 0 && bot.cooldown == 0 && dist < 3.0 && owner.getGameMode() != org.bukkit.GameMode.CREATIVE
                && owner.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            bot.cooldown = random.nextInt(10, 16);
            try { zombie.swingMainHand(); } catch (NoSuchMethodError ignored) { /* older API: no swing animation */ }
            owner.getScheduler().run(AeroACBukkitLoaderPlugin.LOADER, task -> owner.damage(s.damageValue(), zombie), null);
        }
    }

    private Bot botOf(Entity entity) {
        UUID owner = ownerOfEntity.get(entity.getUniqueId());
        return owner == null ? null : bots.get(owner);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Bot bot = botOf(event.getEntity());
        if (bot == null) return;
        bot.grace = 8;
        Zombie zombie = bot.entity;
        double knockback = bot.settings.knockbackValue();
        boolean immortal = bot.settings.immortal;
        // Vanilla sets the knockback velocity during this event's damage; scale it one tick later.
        zombie.getScheduler().runDelayed(AeroACBukkitLoaderPlugin.LOADER, task -> {
            if (knockback != 1.0) {
                Vector v = zombie.getVelocity();
                zombie.setVelocity(new Vector(v.getX() * knockback, knockback == 0 ? Math.min(v.getY(), 0) : v.getY(), v.getZ() * knockback));
            }
            if (immortal) zombie.setHealth(zombie.getMaxHealth());
        }, null, 1L);
        if (immortal && event.getFinalDamage() >= zombie.getHealth()) event.setDamage(Math.max(0, zombie.getHealth() - 1));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnvironmentDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) return;
        if (botOf(event.getEntity()) != null && event.getCause() != EntityDamageEvent.DamageCause.VOID) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (botOf(event.getEntity()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (botOf(event.getEntity()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Bot bot = botOf(event.getEntity());
        if (bot == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        Player owner = org.bukkit.Bukkit.getPlayer(bot.owner);
        if (owner != null) owner.sendMessage(MenuItems.WARN + AeroMessages.tr("gui.bot.died"));
        remove(bot.owner);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    /** Whether an entity type can be a bot; kept for menus that describe it. */
    public static EntityType type() { return EntityType.ZOMBIE; }
}
