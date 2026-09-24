package dev.aeroac.neural;

import ac.grim.grimac.api.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reload publishes a new generation and a new immutable snapshot, always — including when it ends
 * with the module disabled. That is the only thing that lets a GUI, a command or a callback in
 * flight tell "nothing changed" from "everything was rebuilt, and the thing you were looking at no
 * longer exists".
 */
class NeuralGenerationTest {

    private static ConfigManager config(Map<String, Object> values) {
        return (ConfigManager) Proxy.newProxyInstance(NeuralGenerationTest.class.getClassLoader(),
                new Class<?>[]{ConfigManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasLoaded")) return true;
                    if (method.getName().equals("isLoadedAsync")) return false;
                    if (args != null && args.length == 2) {
                        Object override = values.get(String.valueOf(args[0]));
                        if (override != null) return override;
                        return args[1];
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0d;
                    return null;
                });
    }

    private static ConfigManager disabled() {
        return config(Map.of());
    }

    private static ConfigManager recording() {
        return config(Map.of("neural.enabled", true, "neural.collection.enabled", true));
    }

    private static NeuralManager manager(Path dataFolder) {
        return new NeuralManager(() -> dataFolder, NeuralLog.SILENT);
    }

    @Test
    void everyReloadAdvancesTheGenerationEvenWhenTheResultIsDisabled(@TempDir Path dataFolder) {
        NeuralManager manager = manager(dataFolder);
        assertEquals(0, manager.generation(), "nothing has been reloaded yet");
        assertFalse(manager.snapshot().recordingEnabled());

        manager.reload(disabled());
        assertEquals(1, manager.generation());
        assertNull(manager.runtime());

        // The second disabled reload must still move the generation: a reader that saw 1 has to be
        // able to tell that everything was rebuilt, even though the answer stayed "off".
        manager.reload(disabled());
        assertEquals(2, manager.generation());

        manager.reload(recording());
        assertEquals(3, manager.generation());
        assertTrue(manager.snapshot().recordingEnabled());
        assertEquals(3, manager.runtime().generation(),
                "a runtime must carry the generation it was built for");

        manager.stop();
    }

    @Test
    void aDisabledReloadStillPublishesASnapshotWithItsPaths(@TempDir Path dataFolder) {
        NeuralManager manager = manager(dataFolder);
        manager.reload(disabled());

        NeuralSnapshot snapshot = manager.snapshot();
        assertEquals(1, snapshot.generation());
        assertFalse(snapshot.telemetryEnabled());
        assertFalse(snapshot.stopped());
        assertTrue(snapshot.configPath().endsWith("config.yml"));
        assertTrue(Path.of(snapshot.configPath()).isAbsolute());
        assertTrue(snapshot.datasetsPath().endsWith("datasets"));
        assertTrue(snapshot.flags().contains("neural.enabled=false"));
        assertTrue(snapshot.flags().contains("runtime=false"));
        assertTrue(snapshot.flags().contains("stopped=false"));
    }

    @Test
    void anEnabledReloadPublishesARuntimeAndTheFlagsThatProducedIt(@TempDir Path dataFolder) {
        NeuralManager manager = manager(dataFolder);
        manager.reload(recording());

        NeuralSnapshot snapshot = manager.snapshot();
        assertTrue(snapshot.enabled());
        assertTrue(snapshot.collectionEnabled());
        assertTrue(snapshot.recordingEnabled());
        assertTrue(snapshot.telemetryEnabled());
        assertTrue(snapshot.runtime() == manager.runtime());
        assertTrue(Files.exists(dataFolder.resolve("datasets/pseudonym.key")),
                "recording needs the dataset folder, and the key survives every reload");

        manager.stop();
    }

    @Test
    void eachReloadPublishesANewSnapshotAndLeavesTheOldOneIntact(@TempDir Path dataFolder) {
        NeuralManager manager = manager(dataFolder);
        manager.reload(disabled());
        NeuralSnapshot first = manager.snapshot();

        manager.reload(recording());
        NeuralSnapshot second = manager.snapshot();

        assertNotSame(first, second);
        assertEquals(1, first.generation());
        assertEquals(2, second.generation());
        assertFalse(first.recordingEnabled(), "the previous snapshot is not mutated in place");
        assertFalse(first.telemetryEnabled());
        assertTrue(second.recordingEnabled());
        assertNull(first.runtime());
        assertNotSame(first.config(), second.config(),
                "each generation owns its own immutable configuration");

        manager.stop();
    }

    @Test
    void stopPublishesAStoppedSnapshotDrainsTheStorageAndCanBeReloadedAgain(@TempDir Path dataFolder) {
        NeuralManager manager = manager(dataFolder);
        manager.reload(recording());
        long before = manager.generation();

        manager.stop();

        assertEquals(before + 1, manager.generation());
        assertTrue(manager.snapshot().stopped());
        assertNull(manager.runtime());
        assertNull(manager.datasets());
        assertFalse(manager.snapshot().telemetryEnabled());

        // A later reload (plugin re-enable) must build storage again rather than reuse a closed one.
        manager.reload(recording());
        assertTrue(manager.recordingEnabled());
        assertTrue(manager.runtime() != null);
        assertTrue(manager.datasets() != null);
        assertFalse(manager.datasets().closed());

        manager.stop();
    }

    @Test
    void withoutADataFolderTheSnapshotSaysSoInsteadOfFailing() {
        NeuralManager manager = manager(null);
        manager.reload(recording());
        assertEquals(1, manager.generation());
        assertEquals("неизвестно", manager.snapshot().datasetsPath());
        assertNull(manager.datasets());
        manager.stop();
    }

    @Test
    void aControlledSwitchIsRefusedWithoutAConfigurationAndSaysWhy() throws Exception {
        NeuralManager manager = manager(null);
        NeuralManager.EnableResult result = manager.setRecordingEnabled(true).get(10, TimeUnit.SECONDS);

        assertFalse(result.ok());
        assertTrue(result.requested());
        assertFalse(result.message().isBlank());
        assertTrue(result.message().matches(".*[А-Яа-я].*"), result.message());
    }
}
