package dev.aeroac.neural;

import dev.aeroac.neural.risk.Evidence;
import dev.aeroac.neural.risk.EvidenceType;
import dev.aeroac.neural.risk.PlayerRiskProfile;
import dev.aeroac.neural.risk.RiskState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A relog must not be a reset button for accumulated risk. */
class RiskCarryOverTest {
    private static final long SECOND = 1_000_000_000L;

    private static NeuralRuntime runtime(Map<String, Object> overrides) {
        Map<String, Object> values = new java.util.HashMap<>(Map.of(
                "neural.enabled", true, "neural.risk.enabled", true));
        values.putAll(overrides);
        return new NeuralRuntime(1, NeuralConfig.read(NeuralConfigTest.config(values)), null, () -> null);
    }

    private static PlayerRiskProfile suspicious(NeuralRuntime runtime) {
        PlayerRiskProfile profile = new PlayerRiskProfile(16, 0);
        runtime.riskEngine().accept(profile, Evidence.of(EvidenceType.AI_AIM, 8, 0, "test"), 0);
        assertEquals(RiskState.SUSPICIOUS, profile.state());
        return profile;
    }

    @Test void aQuickRelogKeepsTheProfileAndDecaysItOverTheGap() {
        NeuralRuntime runtime = runtime(Map.of("neural.risk.decay-per-second", 0.01));
        UUID player = UUID.randomUUID();
        PlayerRiskProfile before = suspicious(runtime);
        runtime.park(player, before, 0);
        assertEquals(1, runtime.parkedCount());

        NeuralPlayerState rejoined = new NeuralPlayerState();
        PlayerRiskProfile after = runtime.riskProfile(player, rejoined, 30 * SECOND);
        assertSame(before, after, "the evidence behind the number comes back with it");
        assertEquals(0, runtime.parkedCount());
        runtime.riskEngine().decay(after, 30 * SECOND);
        assertEquals(8 * Math.exp(-0.3), after.risk(), 1e-9);
    }

    @Test void anExpiredOrDisabledCarryOverStartsClean() {
        NeuralRuntime runtime = runtime(Map.of("neural.risk.carry-over-seconds", 60));
        UUID player = UUID.randomUUID();
        runtime.park(player, suspicious(runtime), 0);
        PlayerRiskProfile fresh = runtime.riskProfile(player, new NeuralPlayerState(), 61 * SECOND);
        assertEquals(0, fresh.risk());
        assertEquals(RiskState.CLEAN, fresh.state());

        NeuralRuntime off = runtime(Map.of("neural.risk.carry-over-seconds", 0));
        off.park(player, suspicious(off), 0);
        assertEquals(0, off.parkedCount());
    }

    @Test void aCleanProfileIsNotRememberedAndAnotherPlayerGetsNothing() {
        NeuralRuntime runtime = runtime(Map.of());
        runtime.park(UUID.randomUUID(), new PlayerRiskProfile(4, 0), 0);
        assertEquals(0, runtime.parkedCount());
        runtime.park(UUID.randomUUID(), suspicious(runtime), 0);
        PlayerRiskProfile other = runtime.riskProfile(UUID.randomUUID(), new NeuralPlayerState(), SECOND);
        assertEquals(0, other.risk());
        assertEquals(1, runtime.parkedCount());
    }
}
