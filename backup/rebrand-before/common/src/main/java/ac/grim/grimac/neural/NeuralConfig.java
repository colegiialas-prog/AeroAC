package ac.grim.grimac.neural;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.neural.inference.ModelWindow;
import ac.grim.grimac.neural.risk.RiskState;

import java.net.URI;
import java.util.Locale;

/** Immutable snapshot of one reload. Every session, collector and client pins the instance it started with. */
public record NeuralConfig(boolean enabled, boolean collectionEnabled, int continuousSize, int attackBefore,
                           int attackAfter, int targetTimeoutTicks, int queueCapacity, int maxSessions,
                           int maxDurationSeconds, long maxSessionBytes, long maxTotalBytes,
                           int idleTimeoutSeconds, Inference inference, Risk risk, Mitigation mitigation,
                           int monitorIntervalMs, boolean debug) {

    /** A rejected endpoint disables inference instead of silently pointing requests somewhere else. */
    public record Inference(boolean enabled, String endpoint, int timeoutMs, int maxInFlight, int minIntervalMs,
                            ModelWindow flashWindow, int flashSequence, boolean proEnabled, ModelWindow proWindow,
                            int proSequence, double proTrigger, int proMinIntervalMs) { }

    /** acceptUncalibrated exists so an uncalibrated sigmoid cannot silently be treated as a probability. */
    public record Risk(boolean enabled, boolean acceptUncalibrated, double decayPerSecond, double maxRisk,
                       double aiWeight, double aiThreshold, double aiClearThreshold, double aiRelief,
                       double grimWeight, double watch, double suspicious, double confirmed,
                       double snapshotThreshold, int maxSnapshotsPerHour, int snapshotBefore, int snapshotAfter) { }

    public record Mitigation(boolean enabled, RiskState minState, boolean cancelAttacks, int durationSeconds,
                             int maxPerHour) { }

    public static NeuralConfig read(ConfigManager config) {
        int before = bounded(config, "windows.attack-before", 20, 0, 128);
        int after = bounded(config, "windows.attack-after", 10, 0, 128);
        int continuous = Math.max(before + after + 1, bounded(config, "windows.continuous-size", 96, 1, 512));
        return new NeuralConfig(config.getBooleanElse("neural.enabled", false),
                config.getBooleanElse("neural.collection.enabled", false),
                continuous, before, after, bounded(config, "collection.target-timeout-ticks", 40, 1, 200),
                bounded(config, "collection.queue-capacity", 256, 32, 4096),
                bounded(config, "collection.max-sessions", 16, 1, 128),
                bounded(config, "collection.max-duration-seconds", 1800, 10, 14400),
                bounded(config, "collection.max-session-mib", 128, 1, 1024) * 1048576L,
                bounded(config, "collection.max-total-mib", 1024, 16, 1048576) * 1048576L,
                bounded(config, "telemetry.idle-timeout-seconds", 30, 1, 3600),
                inference(config, before + after + 1, continuous), risk(config, continuous), mitigation(config),
                bounded(config, "monitor.interval-ms", 1000, 200, 60000),
                config.getBooleanElse("neural.debug.enabled", false));
    }

    private static Inference inference(ConfigManager config, int attackWindow, int continuous) {
        String endpoint = config.getStringElse("neural.inference.endpoint", "http://127.0.0.1:8080/predict");
        boolean remote = "remote".equalsIgnoreCase(config.getStringElse("neural.inference.mode", "remote"));
        ModelWindow flash = ModelWindow.parse(config.getStringElse("neural.inference.flash.window", "attack"));
        ModelWindow pro = ModelWindow.parse(config.getStringElse("neural.inference.pro.window", "continuous"));
        return new Inference(config.getBooleanElse("neural.inference.enabled", false) && remote && usableEndpoint(endpoint),
                endpoint, bounded(config, "inference.timeout-ms", 300, 20, 5000),
                bounded(config, "inference.max-in-flight", 8, 1, 256),
                bounded(config, "inference.min-interval-ms", 500, 0, 60000),
                flash, sequence(config, "inference.flash.sequence-length", 31, flash, attackWindow, continuous),
                config.getBooleanElse("neural.inference.pro.enabled", false),
                pro, sequence(config, "inference.pro.sequence-length", 96, pro, attackWindow, continuous),
                fraction(config, "inference.pro.trigger-overall", 0.5),
                bounded(config, "inference.pro.min-interval-ms", 2000, 0, 600000));
    }

    /**
     * An attack model consumes exactly the configured attack window, so a mismatched length is
     * corrected here instead of emitting a request the service has to reject on every attack.
     */
    private static int sequence(ConfigManager config, String key, int fallback, ModelWindow window,
                                int attackWindow, int continuous) {
        if (window == ModelWindow.ATTACK) return attackWindow;
        return Math.min(continuous, bounded(config, key, fallback, 1, 512));
    }

    private static Risk risk(ConfigManager config, int continuous) {
        double watch = positive(config, "risk.watch", 2.0);
        double suspicious = Math.max(watch, positive(config, "risk.suspicious", 6.0));
        double confirmed = Math.max(suspicious, positive(config, "risk.confirmed", 12.0));
        double aiThreshold = fraction(config, "risk.ai-threshold", 0.80);
        int after = Math.min(continuous - 1, bounded(config, "risk.snapshot-after", 32, 0, 512));
        return new Risk(config.getBooleanElse("neural.risk.enabled", false),
                config.getBooleanElse("neural.risk.accept-uncalibrated", false),
                positive(config, "risk.decay-per-second", 0.01),
                Math.max(confirmed, positive(config, "risk.max-risk", 20.0)),
                positive(config, "risk.ai-weight", 0.5), aiThreshold,
                Math.min(aiThreshold, fraction(config, "risk.ai-clear-threshold", 0.20)),
                positive(config, "risk.ai-relief", 0.15), positive(config, "risk.grim-weight", 0.5),
                watch, suspicious, confirmed, Math.max(watch, positive(config, "risk.snapshot-threshold", 8.0)),
                bounded(config, "risk.max-snapshots-per-hour", 12, 0, 1000),
                Math.min(continuous - after, bounded(config, "risk.snapshot-before", 64, 0, 512)), after);
    }

    private static Mitigation mitigation(ConfigManager config) {
        return new Mitigation(config.getBooleanElse("neural.mitigation.enabled", false),
                RiskState.parse(config.getStringElse("neural.mitigation.min-state", "MITIGATED")),
                config.getBooleanElse("neural.mitigation.cancel-attacks", false),
                bounded(config, "mitigation.duration-seconds", 30, 1, 3600),
                bounded(config, "mitigation.max-per-hour", 20, 0, 1000));
    }

    private static boolean usableEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return uri.getHost() != null && (scheme.equals("http") || scheme.equals("https"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static int bounded(ConfigManager config, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, config.getIntElse("neural." + key, fallback)));
    }

    private static double positive(ConfigManager config, String key, double fallback) {
        double value = config.getDoubleElse("neural." + key, fallback);
        return Double.isFinite(value) && value >= 0 ? value : fallback;
    }

    private static double fraction(ConfigManager config, String key, double fallback) {
        return Math.max(0, Math.min(1, positive(config, key, fallback)));
    }

    public boolean recordingEnabled() { return enabled && collectionEnabled; }

    /** Frames are collected only while something downstream consumes them. */
    public boolean telemetryEnabled() {
        return enabled && (collectionEnabled || inference.enabled() || risk.enabled());
    }

    public int attackWindowLength() { return attackBefore + attackAfter + 1; }
}
