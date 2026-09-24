package dev.aeroac.neural;

/**
 * One immutable view of the neural module, valid from the moment a reload published it until the
 * next one replaces it.
 *
 * <p>A reload always publishes a new snapshot, including a reload that ends with the module
 * disabled, and always increments {@link #generation()}. That is what makes the generation usable as
 * a guard: a reader that captured {@code generation=N} can tell that the runtime, thresholds and
 * window sizes it was looking at no longer exist, instead of silently mixing two configurations.
 *
 * <p>Nothing here can be written through. The administrator interface reads this object to render
 * state; it never reaches into {@link NeuralRuntime} to change anything, and it never sees a
 * half-applied reload because a snapshot is assigned in one write.
 *
 * <p>{@code configPath} and {@code datasetsPath} are absolute, so a log line or a screen can tell an
 * operator which file on which disk produced the state they are looking at.
 */
public record NeuralSnapshot(long generation, boolean enabled, boolean collectionEnabled,
                             boolean inferenceEnabled, boolean riskEnabled, boolean mitigationEnabled,
                             boolean runtimeActive, boolean stopped, String configPath,
                             String datasetsPath, long reloadedAtMillis, NeuralConfig config,
                             NeuralRuntime runtime) {

    /** Before the first reload: nothing is enabled and nothing has ever run. */
    public static final NeuralSnapshot INITIAL = new NeuralSnapshot(0, false, false, false, false, false,
            false, false, "неизвестно", "неизвестно", 0L, null, null);

    public NeuralSnapshot {
        if (runtimeActive != (runtime != null)) {
            throw new IllegalArgumentException("Snapshot runtime flag disagrees with its runtime");
        }
        if (stopped && runtime != null) {
            throw new IllegalArgumentException("A stopped snapshot cannot own a runtime");
        }
        if (configPath == null) configPath = "неизвестно";
        if (datasetsPath == null) datasetsPath = "неизвестно";
    }

    /** Builds the snapshot a reload publishes. */
    public static NeuralSnapshot of(long generation, NeuralConfig config, NeuralRuntime runtime,
                                    String configPath, String datasetsPath, long reloadedAtMillis) {
        return new NeuralSnapshot(generation, config != null && config.enabled(),
                config != null && config.collectionEnabled(),
                config != null && config.inference().enabled(),
                config != null && config.risk().enabled(),
                config != null && config.mitigation().enabled(),
                runtime != null, false, configPath, datasetsPath, reloadedAtMillis, config, runtime);
    }

    /** The snapshot published by stop: generation advanced, nothing running, nothing enabled. */
    public static NeuralSnapshot stopped(long generation, NeuralConfig config, String configPath,
                                         String datasetsPath, long stoppedAtMillis) {
        return new NeuralSnapshot(generation, false, false, false, false, false, false, true,
                configPath, datasetsPath, stoppedAtMillis, config, null);
    }

    /** Recording needs both switches; a snapshot is the only thing an operator screen has to read. */
    public boolean recordingEnabled() {
        return enabled && collectionEnabled;
    }

    /** Telemetry only exists while a runtime owns it, which is stricter than the config flags. */
    public boolean telemetryEnabled() {
        return runtimeActive;
    }

    /** One line for the startup/reload log and for diagnostics screens. */
    public String flags() {
        return "neural.enabled=" + enabled + ", neural.collection.enabled=" + collectionEnabled
                + ", inference=" + inferenceEnabled + ", risk=" + riskEnabled
                + ", mitigation=" + mitigationEnabled + ", runtime=" + runtimeActive
                + ", stopped=" + stopped;
    }
}
