package dev.aeroac.neural.enforcement;

import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.neural.risk.RiskState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * When the anticheat is allowed to ask for a ban, who decides, and how the moment looks.
 *
 * <p>The default is {@link Mode#ANNOUNCE}, and that is not timidity. The model behind the risk
 * value has never been calibrated against a labelled dataset recorded on this server; every screen
 * in the interface says so. A detector in that state pointed at an automatic ban will remove honest
 * players, and the operator will not find out until somebody appeals. So the default tells the
 * staff what it would have done and lets a person press the button, and {@link Mode#AUTOMATIC} is
 * something an operator switches on deliberately once they have measured the false positive rate on
 * their own server.
 *
 * <p>Nothing here changes how risk is computed. The thresholds below are a gate on an already
 * finished verdict: state, accumulated evidence and how much the model has actually seen.
 */
public record BanPolicy(Mode mode, RiskState minState, int minEvidence, int minPredictions,
                        int cooldownSeconds, int confirmTimeoutSeconds, String command, String reason,
                        Animation animation) {

    public enum Mode {
        /** Nothing is decided and nothing is announced. */
        OFF,
        /** Staff are told what would have happened; a person confirms or declines. */
        ANNOUNCE,
        /** The ban runs on its own. Only sensible after measuring the false positive rate. */
        AUTOMATIC;

        public static Mode parse(String name, Mode fallback) {
            if (name == null) return fallback;
            String cleaned = name.trim().toUpperCase(Locale.ROOT);
            for (Mode mode : values()) if (mode.name().equals(cleaned)) return mode;
            return fallback;
        }
    }

    /**
     * One sound: a key, and how it is played.
     *
     * <p>Keys, not enum constants. {@code Sound} stopped being an enum in recent versions and its
     * constants were renamed before that, so a constant is a crash waiting for the next update. A key
     * the client does not know is simply silent.
     *
     * <p>Written in config as {@code "key pitch volume"}, space separated, because a sound key may
     * carry a {@code minecraft:} namespace and a colon would be ambiguous.
     */
    public record Cue(String sound, float pitch, float volume) {
        private static final Pattern KEY = Pattern.compile("[a-z0-9_.:/-]{1,96}");

        /**
         * Exactly {@code key [pitch [volume]]}, or null.
         *
         * <p>Strict on purpose. "entity warden sonic boom" written with spaces instead of dots
         * would otherwise parse as the sound "entity" with two numbers that are not numbers, and
         * the operator would hear silence with no idea why. A line that is not exactly a cue is
         * dropped whole, so a typo costs one sound and the rest of the bang still plays.
         */
        public static Cue parse(String spec, float defaultPitch, float defaultVolume) {
            if (spec == null) return null;
            String[] parts = spec.trim().split("\\s+");
            if (parts.length == 0 || parts.length > 3 || parts[0].isEmpty()) return null;
            String key = parts[0].toLowerCase(Locale.ROOT);
            if (!KEY.matcher(key).matches()) return null;
            Float pitch = parts.length > 1 ? number(parts[1], 0.5f, 2.0f) : Float.valueOf(defaultPitch);
            Float volume = parts.length > 2 ? number(parts[2], 0.1f, 4.0f) : Float.valueOf(defaultVolume);
            if (pitch == null || volume == null) return null;
            return new Cue(key, pitch, volume);
        }

        /** Clamped into range; null when the text is not a number at all. */
        private static Float number(String text, float min, float max) {
            try {
                float value = Float.parseFloat(text);
                return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : null;
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
    }

    /**
     * The send-off.
     *
     * <p>Cosmetic from end to end: it decides nothing, proves nothing, and a ban that ran without
     * it is exactly the same ban. It exists so the moment is visible to everyone watching.
     *
     * <p>{@code charge} is played once a second on the way up with its pitch climbing towards the
     * top, which is what makes five seconds of floating feel like something is about to happen.
     * {@code bang} is layered: several sounds at once, because any one of them alone is just a sound
     * the player has heard a thousand times.
     */
    public record Animation(boolean enabled, int seconds, boolean scatterInventory, int levitation,
                            boolean glow, boolean lightning, boolean title, boolean broadcast,
                            Cue ascend, Cue charge, Cue scatter, List<Cue> bang) {

        /** Ticks in the whole flight. */
        public long ticks() { return seconds * 20L; }
    }

    /** The default bang: a sonic boom, a thunderclap and a firework blast on top of each other. */
    private static final List<String> DEFAULT_BANG = List.of(
            "entity.warden.sonic_boom 0.7 2.0",
            "entity.lightning_bolt.impact 0.8 1.5",
            "entity.firework_rocket.large_blast 0.6 1.5");

    public static BanPolicy read(ConfigManager config) {
        String prefix = "neural.enforcement.animation.";
        return new BanPolicy(
                Mode.parse(config.getStringElse("neural.enforcement.mode", "announce"), Mode.ANNOUNCE),
                RiskState.parse(config.getStringElse("neural.enforcement.min-state", "CONFIRMED")),
                bounded(config, "neural.enforcement.min-evidence", 12, 1, 4096),
                bounded(config, "neural.enforcement.min-predictions", 10, 1, 4096),
                bounded(config, "neural.enforcement.cooldown-seconds", 600, 1, 86400),
                bounded(config, "neural.enforcement.confirm-timeout-seconds", 900, 30, 86400),
                command(config.getStringElse("neural.enforcement.command", "ban {player} {reason}")),
                trim(config.getStringElse("neural.enforcement.reason", "Aero AC: unfair advantage")),
                new Animation(
                        config.getBooleanElse(prefix + "enabled", true),
                        bounded(config, prefix + "seconds", 5, 1, 30),
                        config.getBooleanElse(prefix + "scatter-inventory", true),
                        bounded(config, prefix + "levitation-amplifier", 1, 0, 10),
                        config.getBooleanElse(prefix + "glow", true),
                        config.getBooleanElse(prefix + "lightning", true),
                        config.getBooleanElse(prefix + "title", true),
                        config.getBooleanElse(prefix + "broadcast", true),
                        Cue.parse(config.getStringElse(prefix + "ascend-sound", "block.beacon.activate 0.6 1.5"), 0.6f, 1.5f),
                        Cue.parse(config.getStringElse(prefix + "charge-sound", "block.note_block.bass 0.5 1.0"), 0.5f, 1.0f),
                        Cue.parse(config.getStringElse(prefix + "scatter-sound", "entity.item.break 1.2 0.8"), 1.2f, 0.8f),
                        cues(config.getStringListElse(prefix + "ban-sounds", DEFAULT_BANG))));
    }

    private static List<Cue> cues(List<String> specs) {
        List<Cue> result = new ArrayList<>();
        if (specs != null) {
            for (String spec : specs) {
                Cue cue = Cue.parse(spec, 1.0f, 1.5f);
                if (cue != null && result.size() < 8) result.add(cue);
            }
        }
        return List.copyOf(result);
    }

    /** True when a verdict at this state, with this much behind it, may be acted on at all. */
    public boolean admits(RiskState state, int evidence, int predictions) {
        return mode != Mode.OFF && state != null && state.atLeast(minState)
                && evidence >= minEvidence && predictions >= minPredictions;
    }

    /**
     * The command to run, with the placeholders filled in.
     *
     * <p>The player name is the only value that did not come from the operator's own config, and a
     * Minecraft name cannot contain a space or a slash, so it cannot turn one command into two.
     * It is still checked, because "cannot" is a property of vanilla and this runs on servers that
     * are not.
     */
    public String commandFor(String player) {
        String safe = player == null ? "" : player.replaceAll("[^A-Za-z0-9_.-]", "");
        return command.replace("{player}", safe).replace("{reason}", reason);
    }

    public boolean automatic() { return mode == Mode.AUTOMATIC; }

    public boolean announces() { return mode == Mode.ANNOUNCE; }

    /** A leading slash is what an operator types in chat; the console does not want it. */
    private static String command(String value) {
        String trimmed = trim(value);
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        return trimmed.isEmpty() ? "ban {player} {reason}" : trimmed;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    private static int bounded(ConfigManager config, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, config.getIntElse(key, fallback)));
    }
}
