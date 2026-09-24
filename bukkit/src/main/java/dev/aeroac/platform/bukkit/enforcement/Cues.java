package dev.aeroac.platform.bukkit.enforcement;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Sounds, particles and effects that survive a version change.
 *
 * <p>All three are moving targets across Minecraft versions: particle constants get renamed between
 * releases, {@code Sound} stopped being an enum entirely, and potion effect constants moved behind a
 * registry. A cue that throws {@code NoSuchFieldError} on somebody's server would take the whole
 * send-off with it, so nothing here is referenced by constant.
 *
 * <p>Sounds go out as raw keys through the String overload, which every supported version accepts
 * and which an unknown key simply makes silent. Particles and effects are looked up by name against
 * a list of spellings, and a spelling nobody recognises leaves that one effect out. Failure here is
 * always a missing effect, never a missing ban.
 */
public final class Cues {

    /** The coil of light around the player on the way up. */
    public static final String[] COIL = {"END_ROD"};
    /** Small blue flames at their feet. */
    public static final String[] EMBERS = {"SOUL_FIRE_FLAME", "FLAME"};
    /** A small puff where each item leaves them. Renamed in 1.20.5. */
    public static final String[] POOF = {"POOF", "EXPLOSION_NORMAL", "CLOUD"};
    /** The bang itself. Renamed in 1.20.5. */
    public static final String[] BANG = {"EXPLOSION_EMITTER", "EXPLOSION_HUGE"};
    /** The burst of colour that makes the bang look like this bang. Renamed in 1.20.5. */
    public static final String[] BURST = {"TOTEM_OF_UNDYING", "TOTEM"};
    /** What hangs in the air afterwards. Renamed in 1.20.5. */
    public static final String[] SMOKE = {"LARGE_SMOKE", "SMOKE_LARGE"};

    private static final Map<String, Optional<Particle>> PARTICLES = new HashMap<>();
    private static final Map<String, Optional<PotionEffectType>> EFFECTS = new HashMap<>();

    private Cues() { }

    /** Plays a sound by its key. A key this server does not know is silence, not an exception. */
    public static void sound(World world, Location where, String key, float volume, float pitch) {
        if (world == null || where == null || key == null || key.isBlank()) return;
        try {
            world.playSound(where, key, volume, pitch);
        } catch (RuntimeException | LinkageError unsupported) {
            // A refused sound is cosmetic; the caller has nothing to do about it.
        }
    }

    /**
     * Spawns the first particle of {@code candidates} this server recognises.
     *
     * <p>The lookup is cached, the negative result included, so a version that knows none of the
     * spellings pays for one failed lookup for the life of the process rather than one per frame.
     */
    public static void particle(World world, Location where, String[] candidates, int count,
                                double spreadX, double spreadY, double spreadZ, double extra) {
        if (world == null || where == null || candidates == null) return;
        Particle particle = particle(candidates);
        if (particle == null) return;
        try {
            world.spawnParticle(particle, where, count, spreadX, spreadY, spreadZ, extra);
        } catch (RuntimeException | LinkageError unsupported) {
            // Some particles need extra data on some versions; skipping one is not a failure.
        }
    }

    static synchronized Particle particle(String[] candidates) {
        String cacheKey = String.join("|", candidates);
        Optional<Particle> cached = PARTICLES.get(cacheKey);
        if (cached != null) return cached.orElse(null);
        Particle found = null;
        for (String name : candidates) {
            try {
                found = Particle.valueOf(name);
                break;
            } catch (IllegalArgumentException | LinkageError notOnThisVersion) {
                // Try the next spelling.
            }
        }
        PARTICLES.put(cacheKey, Optional.ofNullable(found));
        return found;
    }

    /**
     * A potion effect by its vanilla name, or null when this server has none by that name.
     *
     * <p>Tries the registry key first, which is what recent versions are built around, and the
     * legacy name lookup after it, which is what older ones have.
     */
    @SuppressWarnings("deprecation")
    public static synchronized PotionEffectType effect(String name) {
        Optional<PotionEffectType> cached = EFFECTS.get(name);
        if (cached != null) return cached.orElse(null);
        PotionEffectType found = null;
        try {
            found = PotionEffectType.getByKey(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        } catch (RuntimeException | LinkageError notThisWay) {
            // Fall through to the legacy lookup.
        }
        if (found == null) {
            try {
                found = PotionEffectType.getByName(name);
            } catch (RuntimeException | LinkageError notThatWayEither) {
                // Nothing by this name on this version.
            }
        }
        EFFECTS.put(name, Optional.ofNullable(found));
        return found;
    }
}
