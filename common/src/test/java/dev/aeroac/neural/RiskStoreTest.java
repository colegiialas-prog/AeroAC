package dev.aeroac.neural;

import dev.aeroac.neural.risk.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RiskStoreTest {
    private static final long SECOND = 1_000_000_000L;

    @Test void riskSurvivesAStoreRestartAndIsTakenOnce() throws Exception {
        Path file = Files.createTempDirectory("aero-risk").resolve("neural").resolve("risk-store.json");
        UUID player = UUID.randomUUID();
        try (RiskStore store = new RiskStore(file, message -> fail(message))) {
            store.put(player, 7.5, 1_000);
            store.put(UUID.randomUUID(), 0, 1_000);
            assertEquals(1, store.size(), "a clean player is not stored");
        }
        assertTrue(Files.isRegularFile(file));
        try (RiskStore reopened = new RiskStore(file, message -> fail(message))) {
            RiskStore.Stored stored = reopened.take(player);
            assertEquals(7.5, stored.risk());
            assertEquals(1_000, stored.savedAtMillis());
            assertNull(reopened.take(player), "taken once, so two logins cannot both inherit it");
        }
    }

    @Test void oldEntriesArePrunedAndACorruptFileIsIgnored() throws Exception {
        Path file = Files.createTempDirectory("aero-risk").resolve("risk-store.json");
        try (RiskStore store = new RiskStore(file, null)) {
            store.put(UUID.randomUUID(), 3, 0);
            store.put(UUID.randomUUID(), 3, 10_000);
            store.prune(10_000, 5_000);
            assertEquals(1, store.size());
        }
        Files.writeString(file, "{ not json");
        String[] warned = {null};
        try (RiskStore broken = new RiskStore(file, message -> warned[0] = message)) {
            assertEquals(0, broken.size());
            assertNotNull(warned[0]);
        }
    }

    @Test void restoredRiskIsDecayedForTheAbsenceAndCapped() {
        RiskEngine engine = new RiskEngine(NeuralConfig.read(NeuralConfigTest.config(Map.of(
                "neural.risk.decay-per-second", 0.001, "neural.risk.restore-cap", 4.0))).risk());
        PlayerRiskProfile recent = new PlayerRiskProfile(8, 0);
        engine.restore(recent, 11.0, 60, 0);
        assertEquals(4.0, recent.risk(), 1e-12, "never more than the cap: the evidence is gone");
        assertEquals(RiskState.WATCH, recent.state());
        PlayerRiskProfile old = new PlayerRiskProfile(8, 0);
        engine.restore(old, 3.0, 3600, 0);
        assertEquals(3.0 * Math.exp(-3.6), old.risk(), 1e-9);
    }

    @Test void timeInAVehicleDoesNotDecayRisk() {
        RiskEngine engine = new RiskEngine(NeuralConfig.read(NeuralConfigTest.config(Map.of(
                "neural.risk.decay-per-second", 0.1))).risk());
        PlayerRiskProfile profile = new PlayerRiskProfile(8, 0);
        engine.accept(profile, Evidence.of(EvidenceType.AI_AIM, 5, 0, "test"), 0);
        engine.hold(profile, 30 * SECOND);
        engine.decay(profile, 30 * SECOND);
        assertEquals(5, profile.risk(), 1e-12);
        engine.decay(profile, 40 * SECOND);
        assertEquals(5 * Math.exp(-1.0), profile.risk(), 1e-9);
    }
}
