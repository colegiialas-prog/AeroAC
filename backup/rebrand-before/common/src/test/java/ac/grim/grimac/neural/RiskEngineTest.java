package ac.grim.grimac.neural;

import ac.grim.grimac.neural.inference.ModelKind;
import ac.grim.grimac.neural.inference.PredictionResult;
import ac.grim.grimac.neural.inference.PredictionTrail;
import ac.grim.grimac.neural.mitigation.MitigationAction;
import ac.grim.grimac.neural.mitigation.MitigationManager;
import ac.grim.grimac.neural.mitigation.MitigationRule;
import ac.grim.grimac.neural.mitigation.PlayerMitigationState;
import ac.grim.grimac.neural.risk.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RiskEngineTest {
    private static final long SECOND = 1_000_000_000L;

    @Test void riskDecaysExponentiallyAndNeverBelowZero() {
        RiskEngine engine = engine(Map.of("neural.risk.decay-per-second", 0.1));
        PlayerRiskProfile profile = new PlayerRiskProfile(16, 0);
        engine.accept(profile, Evidence.of(EvidenceType.AI_OVERALL, 10, 0, "test"), 0);
        assertEquals(10, profile.risk(), 1.0E-9);
        engine.decay(profile, 10 * SECOND);
        assertEquals(10 * Math.exp(-1.0), profile.risk(), 1.0E-6);
        engine.decay(profile, 10_000 * SECOND);
        assertTrue(profile.risk() >= 0 && profile.risk() < 1.0E-6);
    }

    @Test void decayIsIdempotentForARepeatedTimestamp() {
        RiskEngine engine = engine(Map.of("neural.risk.decay-per-second", 0.5));
        PlayerRiskProfile profile = new PlayerRiskProfile(8, 0);
        engine.accept(profile, Evidence.of(EvidenceType.AI_AIM, 5, 0, "test"), 0);
        engine.decay(profile, 4 * SECOND);
        double once = profile.risk();
        engine.decay(profile, 4 * SECOND);
        engine.decay(profile, 4 * SECOND - 1);
        assertEquals(once, profile.risk(), 1.0E-12);
    }

    @Test void oneMaximalPredictionCannotReachConfirmed() {
        RiskEngine engine = engine(Map.of());
        PlayerRiskProfile profile = new PlayerRiskProfile(64, 0);
        Evidence evidence = engine.fromPrediction(prediction(1, 1.0, 1.0), SECOND);
        assertNotNull(evidence);
        engine.accept(profile, evidence, SECOND);
        assertEquals(RiskState.CLEAN, profile.state());
        assertTrue(profile.risk() <= engine.config().aiWeight() + 1.0E-9);
    }

    @Test void sustainedHighPredictionsEscalateThroughEveryState() {
        RiskEngine engine = engine(Map.of("neural.risk.decay-per-second", 0.0));
        PlayerRiskProfile profile = new PlayerRiskProfile(64, 0);
        RiskState[] seen = new RiskState[]{RiskState.CLEAN, null, null, null};
        for (int i = 1; i <= 40; i++) {
            Evidence evidence = engine.fromPrediction(prediction(i, 1.0, 1.0), i * SECOND);
            engine.accept(profile, evidence, i * SECOND);
            if (profile.state() == RiskState.WATCH) seen[1] = RiskState.WATCH;
            if (profile.state() == RiskState.SUSPICIOUS) seen[2] = RiskState.SUSPICIOUS;
            if (profile.state() == RiskState.CONFIRMED) seen[3] = RiskState.CONFIRMED;
        }
        assertArrayEquals(new RiskState[]{RiskState.CLEAN, RiskState.WATCH, RiskState.SUSPICIOUS, RiskState.CONFIRMED}, seen);
        assertEquals(engine.config().maxRisk(), profile.risk(), 1.0E-9);
        assertEquals(3, profile.transitions());
    }

    @Test void lowProbabilityReliefLowersRiskButCannotGoNegative() {
        RiskEngine engine = engine(Map.of("neural.risk.decay-per-second", 0.0, "neural.risk.ai-relief", 1.0));
        PlayerRiskProfile profile = new PlayerRiskProfile(32, 0);
        engine.accept(profile, Evidence.of(EvidenceType.AI_AIM, 3, 0, "test"), 0);
        Evidence relief = engine.fromPrediction(prediction(2, 0.0, 0.0), SECOND);
        assertEquals(EvidenceType.AI_RELIEF, relief.type());
        assertTrue(relief.strength() < 0);
        for (int i = 0; i < 20; i++) engine.accept(profile, engine.fromPrediction(prediction(i + 3, 0.0, 0.0), SECOND), SECOND);
        assertEquals(0, profile.risk(), 1.0E-9);
        assertEquals(RiskState.CLEAN, profile.state());
    }

    @Test void midRangeProbabilityProducesNoEvidenceAtAll() {
        RiskEngine engine = engine(Map.of());
        assertNull(engine.fromPrediction(prediction(1, 0.5, 0.5), SECOND));
        assertNull(engine.fromPrediction(null, SECOND));
    }

    @Test void anUncalibratedModelIsRefusedUnlessExplicitlyAllowed() {
        assertNull(engine(Map.of()).fromPrediction(uncalibrated(1, 0.99), SECOND));
        assertNotNull(engine(Map.of("neural.risk.accept-uncalibrated", true)).fromPrediction(uncalibrated(1, 0.99), SECOND));
    }

    @Test void theMostSpecificCrossedHeadWins() {
        RiskEngine engine = engine(Map.of());
        assertEquals(EvidenceType.AI_AIM, engine.fromPrediction(
                result(1, "overall", 0.95, "aimAssist", 0.95), SECOND).type());
        assertEquals(EvidenceType.AI_KILLAURA, engine.fromPrediction(
                result(1, "overall", 0.95, "aimAssist", 0.10, "killAura", 0.93), SECOND).type());
        assertEquals(EvidenceType.AI_OVERALL, engine.fromPrediction(
                result(1, "overall", 0.95, "aimAssist", 0.10), SECOND).type());
    }

    @Test void grimFlagsContributeEvidenceWithoutBecomingLabels() {
        RiskEngine engine = engine(Map.of());
        assertEquals(EvidenceType.GRIM_WALL_HIT, engine.fromCheck("WallHit", SECOND).type());
        assertEquals(EvidenceType.GRIM_PACKET_ORDER, engine.fromCheck("PacketOrderC", SECOND).type());
        assertNull(engine.fromCheck("SimulationA", SECOND));
        assertNull(engine.fromCheck(null, SECOND));
        assertNull(engine(Map.of("neural.risk.grim-weight", 0.0)).fromCheck("Reach", SECOND));
    }

    @Test void evidenceRejectsSignsThatContradictItsType() {
        assertThrows(IllegalArgumentException.class, () -> Evidence.of(EvidenceType.AI_AIM, -1, 0, "x"));
        assertThrows(IllegalArgumentException.class, () -> Evidence.of(EvidenceType.AI_RELIEF, 1, 0, "x"));
        assertThrows(IllegalArgumentException.class, () -> Evidence.of(EvidenceType.AI_AIM, Double.NaN, 0, "x"));
    }

    @Test void evidenceHistoryIsBoundedButCountersAreNot() {
        RiskEngine engine = engine(Map.of("neural.risk.decay-per-second", 0.0));
        PlayerRiskProfile profile = new PlayerRiskProfile(4, 0);
        for (int i = 0; i < 50; i++) engine.accept(profile, Evidence.of(EvidenceType.AI_AIM, 0.01, i, "test"), i);
        assertEquals(4, profile.evidenceSize());
        assertEquals(50, profile.count(EvidenceType.AI_AIM));
        assertEquals(0, profile.count(EvidenceType.GRIM_REACH));
    }

    @Test void trailDropsStaleAndDuplicateResponses() {
        PredictionTrail trail = new PredictionTrail(4);
        assertTrue(trail.add(prediction(5, 0.9, 0.9)));
        assertFalse(trail.add(prediction(4, 0.1, 0.1)));
        assertFalse(trail.add(prediction(5, 0.1, 0.1)));
        assertTrue(trail.add(prediction(6, 0.2, 0.2)));
        assertEquals(6, trail.latest().requestId());
        assertEquals(2, trail.accepted());
        assertEquals(2, trail.rejectedStale());
        for (int i = 7; i < 20; i++) trail.add(prediction(i, 0.5, 0.5));
        assertEquals(4, trail.size());
        assertEquals(16, trail.get(0).requestId());
    }

    @Test void mitigationStaysOffUntilTheConfiguredStateIsReached() {
        MitigationManager manager = mitigation(Map.of("neural.mitigation.enabled", true,
                "neural.mitigation.cancel-attacks", true, "neural.mitigation.min-state", "SUSPICIOUS"));
        PlayerMitigationState state = new PlayerMitigationState(8);
        RiskEngine engine = engine(Map.of("neural.risk.decay-per-second", 0.0));
        PlayerRiskProfile profile = new PlayerRiskProfile(32, 0);
        engine.accept(profile, Evidence.of(EvidenceType.AI_AIM, 3, 0, "test"), 0);
        assertNull(manager.evaluate(state, profile, SECOND, "test"));
        assertFalse(manager.shouldCancelAttacks(state, SECOND));
        engine.accept(profile, Evidence.of(EvidenceType.AI_AIM, 4, SECOND, "test"), SECOND);
        MitigationAction action = manager.evaluate(state, profile, SECOND, "test");
        assertNotNull(action);
        assertEquals(MitigationRule.CANCEL_ATTACKS, action.rule());
        assertTrue(manager.shouldCancelAttacks(state, SECOND));
        assertEquals(RiskState.MITIGATED, manager.report(state, profile.state(), SECOND));
    }

    @Test void aRunningMitigationIsNeverExtendedAndExpiresOnItsOwn() {
        MitigationManager manager = mitigation(Map.of("neural.mitigation.enabled", true,
                "neural.mitigation.min-state", "WATCH", "neural.mitigation.duration-seconds", 5));
        PlayerMitigationState state = new PlayerMitigationState(8);
        PlayerRiskProfile profile = confirmed();
        assertNotNull(manager.evaluate(state, profile, SECOND, "first"));
        assertNull(manager.evaluate(state, profile, 2 * SECOND, "second"));
        assertNotNull(state.current(5 * SECOND));
        assertNull(state.current(7 * SECOND));
        assertNotNull(manager.evaluate(state, profile, 7 * SECOND, "third"));
        assertEquals(2, state.size());
    }

    @Test void mitigationReArmsAfterExpiryWithoutNeedingAnotherStateChange() {
        MitigationManager manager = mitigation(Map.of("neural.mitigation.enabled", true,
                "neural.mitigation.min-state", "WATCH", "neural.mitigation.duration-seconds", 2));
        PlayerMitigationState state = new PlayerMitigationState(8);
        PlayerRiskProfile profile = confirmed();
        assertNotNull(manager.evaluate(state, profile, SECOND, "first"));
        // Still CONFIRMED, nothing transitioned, but the first action has expired.
        assertNull(manager.evaluate(state, profile, 2 * SECOND, "while running"));
        assertNotNull(manager.evaluate(state, profile, 4 * SECOND, "after expiry"));
        assertEquals(2, state.size());
    }

    @Test void theHourlyCapBoundsHowOftenAPlayerCanBeMitigated() {
        MitigationManager manager = mitigation(Map.of("neural.mitigation.enabled", true,
                "neural.mitigation.min-state", "WATCH", "neural.mitigation.duration-seconds", 1,
                "neural.mitigation.max-per-hour", 2));
        PlayerMitigationState state = new PlayerMitigationState(8);
        PlayerRiskProfile profile = confirmed();
        assertNotNull(manager.evaluate(state, profile, SECOND, "a"));
        assertNotNull(manager.evaluate(state, profile, 10 * SECOND, "b"));
        assertNull(manager.evaluate(state, profile, 20 * SECOND, "c"));
        assertNotNull(manager.evaluate(state, profile, 3700 * SECOND, "after the window"));
    }

    @Test void disabledMitigationDoesNothingWhateverTheRisk() {
        MitigationManager manager = mitigation(Map.of("neural.mitigation.cancel-attacks", true));
        PlayerMitigationState state = new PlayerMitigationState(4);
        assertNull(manager.evaluate(state, confirmed(), SECOND, "x"));
        assertFalse(manager.shouldCancelAttacks(state, SECOND));
        assertEquals(RiskState.CONFIRMED, manager.report(state, RiskState.CONFIRMED, SECOND));
    }

    @Test void confirmedIsNeverRelabelledAsMitigated() {
        MitigationManager manager = mitigation(Map.of("neural.mitigation.enabled", true,
                "neural.mitigation.min-state", "WATCH"));
        PlayerMitigationState state = new PlayerMitigationState(4);
        manager.evaluate(state, confirmed(), SECOND, "x");
        assertEquals(RiskState.CONFIRMED, manager.report(state, RiskState.CONFIRMED, SECOND));
        assertEquals(RiskState.MITIGATED, manager.report(state, RiskState.SUSPICIOUS, SECOND));
    }

    private static PlayerRiskProfile confirmed() {
        RiskEngine engine = new RiskEngine(NeuralConfig.read(NeuralConfigTest.config(Map.of())).risk());
        PlayerRiskProfile profile = new PlayerRiskProfile(8, 0);
        engine.accept(profile, Evidence.of(EvidenceType.AI_OVERALL, 50, 0, "test"), 0);
        assertEquals(RiskState.CONFIRMED, profile.state());
        return profile;
    }

    private static RiskEngine engine(Map<String, Object> overrides) {
        return new RiskEngine(NeuralConfig.read(NeuralConfigTest.config(overrides)).risk());
    }

    private static MitigationManager mitigation(Map<String, Object> overrides) {
        return new MitigationManager(NeuralConfig.read(NeuralConfigTest.config(overrides)).mitigation());
    }

    private static PredictionResult prediction(long id, double overall, double aim) {
        return result(id, "overall", overall, "aimAssist", aim);
    }

    private static PredictionResult uncalibrated(long id, double overall) {
        return new PredictionResult(id, id * SECOND, ModelKind.FLASH, "test-v1", false,
                new String[]{"overall"}, new double[]{overall}, 5);
    }

    private static PredictionResult result(long id, Object... heads) {
        String[] names = new String[heads.length / 2];
        double[] values = new double[names.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = (String) heads[i * 2];
            values[i] = (Double) heads[i * 2 + 1];
        }
        return new PredictionResult(id, id * SECOND, ModelKind.FLASH, "test-v1", true, names, values, 5);
    }
}
