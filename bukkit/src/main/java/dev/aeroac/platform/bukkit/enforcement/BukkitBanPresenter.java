package dev.aeroac.platform.bukkit.enforcement;

import dev.aeroac.AeroAPI;
import dev.aeroac.locale.AeroMessages;
import dev.aeroac.manager.init.start.StartableInitable;
import dev.aeroac.manager.init.stop.StoppableInitable;
import dev.aeroac.neural.enforcement.BanDecision;
import dev.aeroac.neural.enforcement.BanPolicy;
import dev.aeroac.neural.enforcement.BanPresenter;
import dev.aeroac.platform.api.player.PlatformPlayer;
import dev.aeroac.platform.bukkit.AeroACBukkitLoaderPlugin;
import dev.aeroac.utils.anticheat.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The send-off: a few seconds held in the air, the player's gear coming off them one piece at a
 * time, and a bang.
 *
 * <p>Entirely cosmetic. The verdict was reached and logged before this class was told anything, and
 * the ban runs from the callback whatever happens here — the animation failing, the player
 * disconnecting mid-flight, the plugin unloading. That ordering is the whole contract: a show that
 * can swallow a ban is worse than no show.
 *
 * <p>The flight, frame by frame:
 * <ul>
 *   <li>on the first frame the player is lifted, lit up and told what is happening, and loses every
 *       way to act — see {@link FreezeListener} for the full list;</li>
 *   <li>every frame a coil of light winds up around them;</li>
 *   <li>once a second a low note sounds, a little higher each time, so the whole thing builds;</li>
 *   <li>their inventory leaves them spread across the flight, backpack first and armour last, each
 *       piece thrown outward with a sound of its own;</li>
 *   <li>at the top: a lightning flash, a layered bang, a burst of particles, the server told.</li>
 * </ul>
 *
 * <p>Held state is bounded by the number of players being sent off at once, and every entry has
 * three ways out: the timer finishing, the player quitting, and the plugin stopping. Nobody stays
 * frozen, and nobody hits the ground from the top of the flight — they are given a slow fall on the
 * way out, which matters for a preview and for a ban command that failed.
 */
public final class BukkitBanPresenter implements BanPresenter, StartableInitable, StoppableInitable {
    /** Frame length. Short, because the coil of particles is drawn per frame. */
    static final long FRAME_TICKS = 2L;
    /** A preview's thrown copies disappear after this; the real ones stay to be picked up. */
    private static final int PREVIEW_ITEM_TICKS = 60;
    /** Vanilla despawns an item at this age. */
    private static final int ITEM_DESPAWN_AGE = 6000;
    /** A pickup delay of this value means "never" to the client and the server. */
    private static final int NEVER_PICKUP = 32767;

    /**
     * Where the presenter meets the server: players, the scheduler, the chat.
     *
     * <p>A seam rather than static calls so the flight can be driven frame by frame in a test, and
     * because on Folia "the scheduler" means the player's own region, which is a decision this
     * class should not be making in forty places.
     */
    public interface Stage {
        Player player(UUID id);

        /** Runs {@code frame} every {@code periodTicks} on the player's own thread. */
        Stop repeat(Player player, long periodTicks, Runnable frame, Runnable retired);

        /** Runs {@code task} on the player's own thread, soon. */
        void soon(Player player, Runnable task, Runnable retired);

        void broadcast(String message);
    }

    public interface Stop { void stop(); }

    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private final Supplier<Stage> stage;

    public BukkitBanPresenter() {
        this(ServerStage::new);
    }

    BukkitBanPresenter(Supplier<Stage> stage) {
        this.stage = stage;
    }

    /**
     * Everything the flight took from the player, and the one callback still owed.
     *
     * <p>Restoring matters even though the next thing that usually happens is a ban: a preview
     * ends with the player still on the server, a ban command can fail, and the plugin can unload
     * mid-flight. Each of those must leave a player who can walk.
     */
    private static final class Flight {
        final float walkSpeed;
        final float flySpeed;
        final boolean allowFlight;
        final boolean flying;
        final boolean invulnerable;
        final boolean collidable;
        final Location anchor;
        final boolean preview;
        Runnable onFinished;
        Stop stop;

        Flight(Player player, boolean preview, Runnable onFinished) {
            this.walkSpeed = player.getWalkSpeed();
            this.flySpeed = player.getFlySpeed();
            this.allowFlight = player.getAllowFlight();
            this.flying = player.isFlying();
            this.invulnerable = player.isInvulnerable();
            this.collidable = player.isCollidable();
            this.anchor = player.getLocation().clone();
            this.preview = preview;
            this.onFinished = onFinished;
        }
    }

    @Override public void start() {
        Bukkit.getPluginManager().registerEvents(new FreezeListener(this), AeroACBukkitLoaderPlugin.LOADER);
        AeroAPI.INSTANCE.getBanService().presenter(this);
    }

    @Override public boolean available() { return true; }

    /** Whether this player is mid-flight, which is what the listener asks on every blocked event. */
    public boolean frozen(UUID player) { return flights.containsKey(player); }

    /** Where they were standing; horizontal position is pinned to it for the whole flight. */
    public Location anchor(UUID player) {
        Flight flight = flights.get(player);
        return flight == null ? null : flight.anchor;
    }

    @Override public void present(BanDecision decision, BanPolicy.Animation animation, boolean preview,
                                  Runnable onFinished) {
        Stage current = stage.get();
        Player player = current.player(decision.uuid());
        if (player == null || !player.isOnline()) {
            // Nobody to show it to. The ban is not conditional on the show.
            onFinished.run();
            return;
        }
        current.soon(player, () -> {
            try {
                begin(current, player, decision, animation, preview, onFinished);
            } catch (RuntimeException | LinkageError error) {
                LogUtil.warn("Aero send-off failed for " + player.getName() + "; the ban still runs: " + error);
                if (!release(player.getUniqueId())) onFinished.run();
            }
        }, onFinished);
    }

    private void begin(Stage current, Player player, BanDecision decision, BanPolicy.Animation animation,
                       boolean preview, Runnable onFinished) {
        UUID id = player.getUniqueId();
        if (flights.containsKey(id)) {
            // Already in the air; a second show would fight the first for the same player.
            onFinished.run();
            return;
        }
        Flight flight = new Flight(player, preview, onFinished);
        flights.put(id, flight);

        if (player.isInsideVehicle()) player.leaveVehicle();
        player.closeInventory();
        player.setWalkSpeed(0f);
        player.setFlySpeed(0f);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setInvulnerable(true);
        player.setCollidable(false);
        effect(player, "LEVITATION", (int) animation.ticks() + 40, animation.levitation());
        if (animation.glow()) effect(player, "GLOWING", (int) animation.ticks() + 20, 0);
        if (animation.title()) title(player, decision, animation, preview);
        play(player.getWorld(), player.getLocation(), animation.ascend(), 0f);

        List<Integer> slots = animation.scatterInventory() ? scatterOrder(player.getInventory()) : List.of();
        long total = animation.ticks();
        // Spread across the flight rather than dumped at once: the gear comes off them on the way
        // up. A full inventory still finishes before the top, because the gap never drops below a
        // frame and forty-one frames fit inside the default flight.
        long gap = slots.isEmpty() ? Long.MAX_VALUE : Math.max(FRAME_TICKS, total / (slots.size() + 1L));
        long[] tick = {0};
        int[] thrown = {0};

        Runnable frame = () -> {
            if (!flights.containsKey(id) || !player.isOnline()) {
                release(id);
                return;
            }
            tick[0] += FRAME_TICKS;
            coil(player, tick[0]);
            if (tick[0] % 20 == 0 && tick[0] < total) {
                play(player.getWorld(), player.getLocation(), animation.charge(), (float) tick[0] / total);
            }
            while (thrown[0] < slots.size() && tick[0] >= gap * (thrown[0] + 1L)) {
                scatter(player, slots.get(thrown[0]++), animation, preview);
            }
            if (tick[0] >= total) {
                while (thrown[0] < slots.size()) scatter(player, slots.get(thrown[0]++), animation, preview);
                bang(current, player, decision, animation, preview);
                release(id);
            }
        };
        flight.stop = current.repeat(player, FRAME_TICKS, frame, () -> release(id));
    }

    /** Backpack, then hotbar, then off-hand, then armour — so the last thing to go is what they wore. */
    static List<Integer> scatterOrder(PlayerInventory inventory) {
        List<Integer> order = new ArrayList<>();
        for (int slot = 9; slot <= 35; slot++) order.add(slot);
        for (int slot = 0; slot <= 8; slot++) order.add(slot);
        order.add(40);
        for (int slot = 39; slot >= 36; slot--) order.add(slot);
        List<Integer> occupied = new ArrayList<>();
        int size = inventory.getSize();
        for (int slot : order) {
            if (slot >= size) continue;
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.getType() != Material.AIR && stack.getAmount() > 0) occupied.add(slot);
        }
        return occupied;
    }

    /**
     * One piece leaves the inventory and is thrown outward.
     *
     * <p>In a preview nothing leaves: a copy is thrown instead, nobody can pick it up, and it is
     * gone in three seconds. An operator trying the animation on themselves keeps everything.
     */
    private void scatter(Player player, int slot, BanPolicy.Animation animation, boolean preview) {
        PlayerInventory inventory = player.getInventory();
        ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.getType() == Material.AIR) return;
        if (!preview) {
            inventory.setItem(slot, null);
            player.updateInventory();
        }
        World world = player.getWorld();
        Location from = player.getLocation().add(0, 1.1, 0);
        try {
            Item thrown = world.dropItem(from, preview ? stack.clone() : stack);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double angle = random.nextDouble(Math.PI * 2);
            double speed = random.nextDouble(0.28, 0.45);
            thrown.setVelocity(new Vector(Math.cos(angle) * speed, random.nextDouble(0.12, 0.32),
                    Math.sin(angle) * speed));
            if (preview) {
                thrown.setPickupDelay(NEVER_PICKUP);
                thrown.setTicksLived(ITEM_DESPAWN_AGE - PREVIEW_ITEM_TICKS);
            }
        } catch (RuntimeException | LinkageError error) {
            // A world that refuses the drop is not a reason to abandon the send-off. The item is
            // already out of the inventory in a real ban; put it back rather than lose it.
            if (!preview) inventory.setItem(slot, stack);
            LogUtil.warn("Aero send-off could not throw an item: " + error);
            return;
        }
        float wobble = (float) ThreadLocalRandom.current().nextDouble(-0.12, 0.12);
        play(world, from, animation.scatter(), wobble);
        Cues.particle(world, from, Cues.POOF, 6, 0.25, 0.25, 0.25, 0.02);
    }

    /** Two strands of light winding upward around the player, one step per frame. */
    private void coil(Player player, long tick) {
        Location base = player.getLocation();
        World world = player.getWorld();
        double angle = tick * 0.32;
        double height = (tick % 40) / 40.0 * 2.2;
        for (int strand = 0; strand < 2; strand++) {
            double a = angle + strand * Math.PI;
            Location at = base.clone().add(Math.cos(a) * 0.9, height, Math.sin(a) * 0.9);
            Cues.particle(world, at, Cues.COIL, 1, 0, 0, 0, 0);
        }
        if (tick % 6 == 0) Cues.particle(world, base, Cues.EMBERS, 3, 0.3, 0.05, 0.3, 0.01);
    }

    /**
     * The top of the flight.
     *
     * <p>The lightning is an effect only — no damage, no fire — and brings its own thunder. The
     * configured sounds play on top of it at once, which is what turns "an explosion" into this
     * particular one.
     */
    private void bang(Stage current, Player player, BanDecision decision, BanPolicy.Animation animation,
                      boolean preview) {
        World world = player.getWorld();
        Location where = player.getLocation();
        if (animation.lightning()) {
            try {
                world.strikeLightningEffect(where);
            } catch (RuntimeException | LinkageError unsupported) {
                // Cosmetic.
            }
        }
        for (BanPolicy.Cue cue : animation.bang()) play(world, where, cue, 0f);
        Cues.particle(world, where, Cues.BANG, 1, 0, 0, 0, 0);
        Cues.particle(world, where.clone().add(0, 1, 0), Cues.BURST, 60, 0.4, 0.8, 0.4, 0.35);
        Cues.particle(world, where, Cues.SMOKE, 25, 0.6, 0.6, 0.6, 0.03);
        if (animation.broadcast() && !preview) {
            current.broadcast(AeroMessages.tr("ban.broadcast", decision.name()));
        }
    }

    private void title(Player player, BanDecision decision, BanPolicy.Animation animation, boolean preview) {
        try {
            player.sendTitle(AeroMessages.tr(preview ? "ban.preview_title" : "ban.title"),
                    AeroMessages.tr(preview ? "ban.preview_subtitle" : "ban.subtitle"),
                    8, (int) animation.ticks(), 20);
        } catch (RuntimeException | LinkageError unsupported) {
            // Cosmetic.
        }
    }

    /**
     * Plays one cue. {@code rise} lifts the pitch toward the top of the range as the flight goes on;
     * zero plays it as configured.
     */
    private static void play(World world, Location where, BanPolicy.Cue cue, float rise) {
        if (cue == null) return;
        float pitch = Math.max(0.5f, Math.min(2.0f, cue.pitch() + (2.0f - cue.pitch()) * rise));
        Cues.sound(world, where, cue.sound(), cue.volume(), pitch);
    }

    /**
     * Adds an effect by its registry name.
     *
     * <p>By name because the constants are not stable across versions either, and a missing effect
     * must cost only that effect: a flight without levitation is still a freeze, a scatter and a
     * bang, and the ban still runs.
     */
    private static void effect(Player player, String name, int ticks, int amplifier) {
        PotionEffectType type = Cues.effect(name);
        if (type == null) return;
        try {
            player.addPotionEffect(new PotionEffect(type, ticks, Math.max(0, amplifier), true, false, false));
        } catch (RuntimeException | LinkageError unsupported) {
            // Cosmetic.
        }
    }

    private static void clearEffect(Player player, String name) {
        PotionEffectType type = Cues.effect(name);
        if (type == null) return;
        try {
            player.removePotionEffect(type);
        } catch (RuntimeException | LinkageError ignored) {
            // Best effort.
        }
    }

    /**
     * Gives the player back everything the flight took and settles the callback — once.
     *
     * @return true when there was a flight to end, false when the player was not in the air
     */
    public boolean release(UUID id) {
        Flight flight = flights.remove(id);
        if (flight == null) return false;
        if (flight.stop != null) {
            try {
                flight.stop.stop();
            } catch (RuntimeException ignored) {
                // A timer that cannot be stopped finds no flight on its next frame and does nothing.
            }
        }
        Player player = stage.get().player(id);
        if (player != null) {
            try {
                player.setWalkSpeed(flight.walkSpeed);
                player.setFlySpeed(flight.flySpeed);
                player.setAllowFlight(flight.allowFlight);
                player.setFlying(flight.flying && flight.allowFlight);
                player.setInvulnerable(flight.invulnerable);
                player.setCollidable(flight.collidable);
                clearEffect(player, "LEVITATION");
                clearEffect(player, "GLOWING");
                // They are several blocks up. Whether the ban lands a tick later or the command
                // failed or it was only a preview, nobody should hit the ground from there.
                player.setFallDistance(0f);
                effect(player, "SLOW_FALLING", 200, 0);
            } catch (RuntimeException | LinkageError ignored) {
                // Restoring is best effort; the callback below is not.
            }
        }
        Runnable callback = flight.onFinished;
        flight.onFinished = null;
        if (callback != null) callback.run();
        return true;
    }

    @Override public void cancelAll() {
        for (UUID id : List.copyOf(flights.keySet())) release(id);
    }

    @Override public void stop() {
        cancelAll();
    }

    /** The real server: Bukkit for players and chat, the plugin's own scheduler for time. */
    private static final class ServerStage implements Stage {
        @Override public Player player(UUID id) {
            return Bukkit.getPlayer(id);
        }

        @Override public Stop repeat(Player player, long periodTicks, Runnable frame, Runnable retired) {
            var handle = AeroAPI.INSTANCE.getScheduler().getEntityScheduler().runAtFixedRate(
                    platform(player), AeroAPI.INSTANCE.getGrimPlugin(), frame, retired, periodTicks, periodTicks);
            return handle::cancel;
        }

        @Override public void soon(Player player, Runnable task, Runnable retired) {
            AeroAPI.INSTANCE.getScheduler().getEntityScheduler()
                    .execute(platform(player), AeroAPI.INSTANCE.getGrimPlugin(), task, retired, 1);
        }

        @Override public void broadcast(String message) {
            for (Player online : Bukkit.getOnlinePlayers()) online.sendMessage(message);
            Bukkit.getConsoleSender().sendMessage(message);
        }

        private static PlatformPlayer platform(Player player) {
            var tracked = AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(player.getUniqueId());
            if (tracked != null && tracked.platformPlayer != null) return tracked.platformPlayer;
            return AeroAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(player.getUniqueId());
        }
    }
}
