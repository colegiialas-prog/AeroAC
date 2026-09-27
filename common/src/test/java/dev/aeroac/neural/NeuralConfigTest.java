package dev.aeroac.neural;

import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.neural.inference.ModelWindow;
import dev.aeroac.neural.risk.RiskState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NeuralConfigTest {
    @Test void disabledByDefaultAndRequiresBothSwitches() {
        assertFalse(NeuralConfig.read(config(Map.of())).recordingEnabled());
        assertFalse(NeuralConfig.read(config(Map.of("neural.enabled", true))).recordingEnabled());
        assertFalse(NeuralConfig.read(config(Map.of("neural.collection.enabled", true))).recordingEnabled());
        assertTrue(NeuralConfig.read(config(Map.of("neural.enabled", true, "neural.collection.enabled", true))).recordingEnabled());
    }

    @Test void boundsMemoryAndFitsAttackWindow() {
        NeuralConfig config = NeuralConfig.read(config(Map.of(
                "neural.windows.continuous-size", -10,
                "neural.windows.attack-before", 1000,
                "neural.windows.attack-after", 1000,
                "neural.collection.queue-capacity", Integer.MAX_VALUE,
                "neural.collection.max-sessions", 1000,
                "neural.collection.max-duration-seconds", -1)));
        assertEquals(257, config.continuousSize());
        assertEquals(4096, config.queueCapacity());
        assertEquals(128, config.maxSessions());
        assertEquals(10, config.maxDurationSeconds());
    }

    @Test void telemetryRunsForAnyConsumerNotOnlyRecording() {
        assertFalse(NeuralConfig.read(config(Map.of("neural.enabled", true))).telemetryEnabled());
        assertTrue(NeuralConfig.read(config(Map.of("neural.enabled", true, "neural.risk.enabled", true))).telemetryEnabled());
        assertTrue(NeuralConfig.read(config(Map.of("neural.enabled", true, "neural.inference.enabled", true))).telemetryEnabled());
        // Risk without enabled stays off: one master switch governs the whole module.
        assertFalse(NeuralConfig.read(config(Map.of("neural.risk.enabled", true))).telemetryEnabled());
    }

    @Test void rejectsUnusableEndpointInsteadOfPointingRequestsElsewhere() {
        assertTrue(NeuralConfig.read(config(Map.of("neural.enabled", true, "neural.inference.enabled", true,
                "neural.inference.endpoint", "https://127.0.0.1:9000/predict"))).inference().enabled());
        for (String broken : new String[]{"", "not a url", "file:///etc/passwd", "ftp://host/x", "/predict"}) {
            assertFalse(NeuralConfig.read(config(Map.of("neural.enabled", true, "neural.inference.enabled", true,
                    "neural.inference.endpoint", broken))).inference().enabled(), broken);
        }
        assertFalse(NeuralConfig.read(config(Map.of("neural.enabled", true, "neural.inference.enabled", true,
                "neural.inference.mode", "somewhere"))).inference().enabled(), "an unknown mode serves nothing");
    }

    @Test void localModeServesTheConfiguredBundleWithoutAnEndpoint() {
        NeuralConfig.Inference local = NeuralConfig.read(config(Map.of("neural.enabled", true,
                "neural.inference.enabled", true, "neural.inference.mode", "local",
                "neural.inference.endpoint", "not a url"))).inference();
        assertTrue(local.enabled() && local.local());
        assertEquals("models/flash", local.flashBundle());
        assertEquals(2, local.localThreads());
        assertFalse(NeuralConfig.read(config(Map.of("neural.enabled", true, "neural.inference.enabled", true,
                "neural.inference.mode", "local", "neural.inference.local.flash-bundle", " "))).inference().enabled(),
                "local mode with no bundle serves nothing");
    }

    @Test void attackModelLengthFollowsTheConfiguredWindow() {
        NeuralConfig config = NeuralConfig.read(config(Map.of("neural.windows.attack-before", 12,
                "neural.windows.attack-after", 4, "neural.inference.flash.sequence-length", 999)));
        assertEquals(ModelWindow.ATTACK, config.inference().flashWindow());
        assertEquals(17, config.inference().flashSequence());
        assertEquals(17, config.attackWindowLength());
    }

    @Test void continuousModelLengthCannotExceedTheRing() {
        NeuralConfig config = NeuralConfig.read(config(Map.of("neural.windows.continuous-size", 64,
                "neural.inference.pro.window", "continuous", "neural.inference.pro.sequence-length", 500)));
        assertEquals(64, config.inference().proSequence());
    }

    @Test void riskThresholdsAreMonotonicAndClearCannotExceedAlarm() {
        NeuralConfig.Risk risk = NeuralConfig.read(config(Map.of(
                "neural.risk.watch", 9.0, "neural.risk.suspicious", 1.0, "neural.risk.confirmed", 2.0,
                "neural.risk.max-risk", 0.5, "neural.risk.ai-threshold", 0.4,
                "neural.risk.ai-clear-threshold", 0.9))).risk();
        assertEquals(9.0, risk.watch());
        assertEquals(9.0, risk.suspicious());
        assertEquals(9.0, risk.confirmed());
        assertEquals(9.0, risk.maxRisk());
        assertTrue(risk.aiClearThreshold() <= risk.aiThreshold());
        assertFalse(risk.acceptUncalibrated());
    }

    @Test void snapshotWindowFitsInsideTheRing() {
        NeuralConfig.Risk risk = NeuralConfig.read(config(Map.of("neural.windows.continuous-size", 48,
                "neural.risk.snapshot-before", 400, "neural.risk.snapshot-after", 400))).risk();
        assertEquals(47, risk.snapshotAfter());
        assertEquals(1, risk.snapshotBefore());
        assertTrue(risk.snapshotBefore() + risk.snapshotAfter() <= 48);
    }

    @Test void mitigationDefaultsToOffAndToTheHighestNamedState() {
        NeuralConfig.Mitigation mitigation = NeuralConfig.read(config(Map.of())).mitigation();
        assertFalse(mitigation.enabled());
        assertFalse(mitigation.cancelAttacks());
        assertEquals(RiskState.MITIGATED, mitigation.minState());
        assertEquals(RiskState.CONFIRMED, NeuralConfig.read(config(Map.of(
                "neural.mitigation.min-state", "confirmed"))).mitigation().minState());
        // An unreadable state must not silently become the most permissive one.
        assertEquals(RiskState.MITIGATED, NeuralConfig.read(config(Map.of(
                "neural.mitigation.min-state", "nonsense"))).mitigation().minState());
    }

    static ConfigManager config(Map<String, Object> values) {
        return (ConfigManager) Proxy.newProxyInstance(ConfigManager.class.getClassLoader(), new Class<?>[]{ConfigManager.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getBooleanElse":
                        case "getIntElse":
                        case "getLongElse":
                        case "getDoubleElse":
                        case "getStringElse":
                            return values.getOrDefault((String) arguments[0], arguments[1]);
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
