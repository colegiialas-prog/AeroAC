package dev.aeroac.neural.admin;

import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.neural.risk.RiskState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Immutable snapshot of the administrator interface settings for one reload.
 *
 * <p>Everything here is presentation and pacing. No value in this record can change a prediction, a
 * risk value, a mitigation or what a dataset session records; the collection targets in particular
 * are recommendations shown to an operator, never a quality judgement and never part of a label.
 */
public record AdminConfig(boolean enabled, int refreshMs, Floating floating, Alerts alerts, Training training,
                          Dataset dataset) {

    public record Floating(boolean enabled, int refreshMs, AdminViewMode defaultMode) { }

    /** Alerts fire on a state transition, not on a prediction, and never more than once per throttle. */
    public record Alerts(boolean enabled, RiskState minState, int throttleSeconds) { }

    /**
     * UI-only collection goals. They exist so an operator can see how far a recording has got, and
     * are deliberately not inputs to anything: a session that misses them is not "bad data", and a
     * session that meets them is not reviewed, calibrated or usable by that fact alone.
     */
    public record Training(int targetDurationSeconds, int targetAttackWindows, List<String> scenarios,
                           List<String> cheatFamilies, List<String> clientFamilies,
                           List<String> configurations,
                           int sessionsPerAssistStrength, int distinctCheatClients, int highPingLegitSessions) { }

    /** Dataset summary reads session metadata off the main thread and caches the result. */
    public record Dataset(int cacheSeconds, int maxSessions, int recentSessions) { }

    private static final List<String> DEFAULT_SCENARIOS =
            List.of("box-pvp", "tracking", "flick", "strafe", "flat-duel", "custom");

    /**
     * Starting points, not a closed set.
     *
     * <p>A server recording a family this list does not know about must be able to name it rather
     * than squeeze it into the nearest wrong word, so both lists are configuration and the wizard
     * always offers a free-text route as well.
     */
    private static final List<String> DEFAULT_CHEAT_FAMILIES =
            List.of("aim-assist", "kill-aura", "trigger-bot", "reach", "auto-clicker", "other");

    /**
     * Clients an operator is likely to be recording, honest ones first.
     *
     * <p>A wizard step with an empty list is a blank screen that an operator has to guess their way
     * out of, which is exactly how the first version of this was unusable. Every step now starts
     * with something to click, and the free-text route stays for the names this list cannot know —
     * a cheat client's own name, most of all.
     */
    private static final List<String> DEFAULT_CLIENT_FAMILIES =
            List.of("vanilla", "lunar", "badlion", "feather", "labymod", "forge", "fabric", "other");

    /** How the client was set up for this recording. Free-text stays available for anything else. */
    private static final List<String> DEFAULT_CONFIGURATIONS =
            List.of("default", "subtle", "aggressive", "legit-mode", "custom");

    public static AdminConfig read(ConfigManager config) {
        return new AdminConfig(
                config.getBooleanElse("neural.gui.enabled", true),
                bounded(config, "gui.refresh-ms", 1000, 200, 60000),
                new Floating(config.getBooleanElse("neural.gui.floating.enabled", true),
                        bounded(config, "gui.floating.refresh-ms", 500, 200, 60000),
                        AdminViewMode.parse(config.getStringElse("neural.gui.floating.default-mode", "suspicious"),
                                AdminViewMode.SUSPICIOUS)),
                new Alerts(config.getBooleanElse("neural.gui.alerts.enabled", true),
                        RiskState.parse(config.getStringElse("neural.gui.alerts.min-state", "WATCH"), RiskState.WATCH),
                        bounded(config, "gui.alerts.throttle-seconds", 30, 1, 3600)),
                new Training(bounded(config, "gui.training.target-duration-seconds", 300, 10, 14400),
                        bounded(config, "gui.training.target-attack-windows", 150, 1, 100000),
                        presets(config, "neural.gui.training.scenarios", DEFAULT_SCENARIOS),
                        presets(config, "neural.gui.training.cheat-families", DEFAULT_CHEAT_FAMILIES),
                        presets(config, "neural.gui.training.client-families", DEFAULT_CLIENT_FAMILIES),
                        presets(config, "neural.gui.training.configurations", DEFAULT_CONFIGURATIONS),
                        bounded(config, "gui.training.goal-sessions-per-assist-strength", 20, 1, 100000),
                        bounded(config, "gui.training.goal-distinct-cheat-clients", 5, 1, 1000),
                        bounded(config, "gui.training.goal-high-ping-legit-sessions", 20, 1, 100000)),
                new Dataset(bounded(config, "gui.dataset.cache-seconds", 60, 5, 3600),
                        bounded(config, "gui.dataset.max-sessions", 5000, 1, 200000),
                        bounded(config, "gui.dataset.recent-sessions", 45, 1, 200)));
    }

    /**
     * Normalised preset list, with the caller's default when nothing usable is configured.
     *
     * <p>Normalisation matches the recorder's, so a preset picked here and a value typed by hand
     * cannot become two different groups in the dataset.
     */
    private static List<String> presets(ConfigManager config, String key, List<String> fallback) {
        List<String> configured = config.getStringListElse(key, fallback);
        List<String> cleaned = new ArrayList<>();
        for (String value : configured) {
            if (value == null) continue;
            String normalised = value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
            if (!normalised.isEmpty() && !cleaned.contains(normalised) && cleaned.size() < 45) {
                cleaned.add(normalised);
            }
        }
        return cleaned.isEmpty() ? List.copyOf(fallback) : List.copyOf(cleaned);
    }

    private static int bounded(ConfigManager config, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, config.getIntElse("neural." + key, fallback)));
    }

    /** Refresh interval in whole server ticks, at least one. */
    public long refreshTicks() { return Math.max(1L, refreshMs / 50L); }

    public long floatingTicks() { return Math.max(1L, floating.refreshMs() / 50L); }
}
