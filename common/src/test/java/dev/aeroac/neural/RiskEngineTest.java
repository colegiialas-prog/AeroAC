package dev.aeroac.neural;

import dev.aeroac.neural.inference.ModelKind;
import dev.aeroac.neural.inference.PredictionResult;
import dev.aeroac.neural.inference.PredictionTrail;
import dev.aeroac.neural.mitigation.MitigationAction;
import dev.aeroac.neural.mitigation.MitigationManager;
import dev.aeroac.neural.mitigation.MitigationRule;
import dev.aeroac.neural.mitigation.PlayerMitigationState;
import dev.aeroac.neural.risk.*;
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
        assertEquals(EvidenceType.GRIM_AIR_STUCK, engine.fromCheck("AirStuck", SECOND).type());
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

    @Test void aSlowProAnswerIsNotStaleBehindANewerFlashAnswer() {
        PredictionTrail trail = new PredictionTrail(8);
        PredictionResult flash = new PredictionResult(8, 8 * SECOND, ModelKind.FLASH, "f", true,
                new String[]{"overall"}, new double[]{0.9}, 5);
        PredictionResult pro = new PredictionResult(7, 9 * SECOND, ModelKind.PRO, "p", true,
                new String[]{"overall"}, new double[]{0.95}, 80);
        PredictionResult oldFlash = new PredictionResult(6, 10 * SECOND, ModelKind.FLASH, "f", true,
                new String[]{"overall"}, new double[]{0.1}, 5);
        assertTrue(trail.add(flash));
        assertTrue(trail.add(pro), "Pro id 7 answered after Flash id 8 is still Pro's newest answer");
        assertFalse(trail.add(oldFlash), "an older Flash answer is still stale");
        assertFalse(trail.add(pro), "a duplicate is still stale");
        assertEquals(2, trail.accepted());
        assertEquals(2, trail.rejectedStale());
        trail.clear();
        assertTrue(trail.add(oldFlash));
    }

    @Test void aMisspelledStateNeverLowersTheSettingItConfigures() {
        assertEquals(RiskState.CONFIRMED, RiskState.parse("CONFIRMD", RiskState.CONFIRMED));
        assertEquals(RiskState.WATCH, RiskState.parse(null, RiskState.WATCH));
        assertEquals(RiskState.SUSPICIOUS, RiskState.parse(" suspicious ", RiskState.CONFIRMED));
    }

    @Test void theHourlyCapWindowDoesNotDependOnTheClockOrigin() {
        // System.nanoTime() may be negative or small; the cap must still reopen an hour later.
        MitigationManager manager = mitigation(Map.of("neural.mitigation.enabled", true,
                "neural.mitigation.min-state", "WATCH", "neural.mitigation.duration-seconds", 5,
                "neural.mitigation.max-per-hour", 1));
        PlayerMitigationState state = new PlayerMitigationState(8);
        PlayerRiskProfile profile = confirmed();
        long start = -10L * 3600 * SECOND;
        assertNotNull(manager.evaluate(state, profile, start, "test"));
        assertNull(manager.evaluate(state, profile, start + 60 * SECOND, "test"), "capped inside the hour");
        assertNotNull(manager.evaluate(state, profile, start + 3601 * SECOND, "test"), "reopened after an hour");
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

    /** The original tests describe the threshold rule; log-odds tests opt in explicitly. */
    private static RiskEngine engine(Map<String, Object> overrides) {
        Map<String, Object> settings = new java.util.HashMap<>();
        settings.put("neural.risk.ai-scoring", "threshold");
        settings.putAll(overrides);
        return new RiskEngine(NeuralConfig.read(NeuralConfigTest.config(settings)).risk());
    }

    private static RiskEngine logOdds(Map<String, Object> overrides) {
        Map<String, Object> settings = new java.util.HashMap<>(overrides);
        settings.put("neural.risk.ai-scoring", "log-odds");
        return new RiskEngine(NeuralConfig.read(NeuralConfigTest.config(settings)).risk());
    }

    @Test void logOddsIsTheDefaultScoring() {
        assertTrue(NeuralConfig.read(NeuralConfigTest.config(Map.of())).risk().logOdds());
    }

    @Test void logOddsCountsTheOldDeadBandAndIsZeroAtTheNeutralRate() {
        RiskEngine engine = logOdds(Map.of());
        assertNull(engine.fromPrediction(prediction(1, 0.5, 0.5), SECOND), "p = prior carries no evidence");
        Evidence moderate = engine.fromPrediction(prediction(2, 0.7, 0.7), SECOND);
        assertEquals(EvidenceType.AI_OVERALL, moderate.type());
        assertEquals(0.5 * Math.log(0.7 / 0.3), moderate.strength(), 1.0E-12);
    }

    @Test void logOddsHonoursOverlapShareClampAndReportedPrior() {
        RiskEngine engine = logOdds(Map.of());
        double full = engine.fromPrediction(prediction(1, 1.0, 1.0), 1.0, SECOND).strength();
        assertEquals(0.5 * Math.log(0.98 / 0.02), full, 1.0E-12, "1.0 is clamped to 0.98");
        assertEquals(full / 3, engine.fromPrediction(prediction(2, 1.0, 1.0), 1.0 / 3, SECOND).strength(), 1.0E-12);
        assertNull(engine.fromPrediction(prediction(3, 1.0, 1.0), 0.0, SECOND), "a fully overlapped window adds nothing");
        PredictionResult withPrior = new PredictionResult(4, SECOND, ModelKind.FLASH, "v", true,
                new String[]{"overall"}, new double[]{0.2}, 1, 0.2);
        assertNull(engine.fromPrediction(withPrior, SECOND), "p equal to the reported base rate is neutral");
    }

    @Test void logOddsReliefIsScaledAndNeverRaisesRisk() {
        RiskEngine engine = logOdds(Map.of("neural.risk.relief-scale", 0.25));
        Evidence relief = engine.fromPrediction(prediction(1, 0.1, 0.1), SECOND);
        assertEquals(EvidenceType.AI_RELIEF, relief.type());
        assertEquals(0.25 * 0.5 * (Math.log(0.1 / 0.9)), relief.strength(), 1.0E-12);
        assertNull(logOdds(Map.of()).fromPrediction(uncalibrated(2, 0.99), SECOND));
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
