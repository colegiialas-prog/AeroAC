package dev.aeroac.neural;

import dev.aeroac.neural.inference.ModelKind;
import dev.aeroac.neural.inference.PredictionResult;
import dev.aeroac.neural.risk.Evidence;
import dev.aeroac.neural.risk.PlayerRiskProfile;
import dev.aeroac.neural.risk.RiskEngine;
import dev.aeroac.neural.risk.RiskState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Companion to ml/tests/test_risk_sim.py. The offline simulator is what decides whether a
 * mitigation threshold is safe; this engine is what actually acts on a player. If the two drift,
 * every offline false-positive number describes a program nobody is running.
 *
 * <p>Regenerate with {@code python -m aeroml.tools.make_risk_golden} from ml/, then run both suites.
 */
class RiskSimulationGoldenTest {
    private static final double TOLERANCE = 1.0E-9;

    @Test void javaReproducesTheCanonicalRiskTrace() throws Exception {
        replay(fixture("risk_golden.json"));
    }

    @Test void javaReproducesTheLogOddsRiskTrace() throws Exception {
        replay(fixture("risk_golden_log_odds.json"));
    }

    private static void replay(JsonObject fixture) {
        JsonObject config = fixture.getAsJsonObject("config");
        Map<String, Object> settings = new java.util.HashMap<>(Map.of(
                "neural.risk.ai-scoring", config.get("aiScoring").getAsString(),
                "neural.risk.log-odds-weight", config.get("logOddsWeight").getAsDouble(),
                "neural.risk.ai-neutral", config.get("aiNeutral").getAsDouble(),
                "neural.risk.ai-clamp-low", config.get("aiClampLow").getAsDouble(),
                "neural.risk.ai-clamp-high", config.get("aiClampHigh").getAsDouble(),
                "neural.risk.relief-scale", config.get("reliefScale").getAsDouble()));
        settings.putAll(Map.of(
                "neural.risk.accept-uncalibrated", config.get("acceptUncalibrated").getAsBoolean(),
                "neural.risk.decay-per-second", config.get("decayPerSecond").getAsDouble(),
                "neural.risk.max-risk", config.get("maxRisk").getAsDouble(),
                "neural.risk.ai-weight", config.get("aiWeight").getAsDouble(),
                "neural.risk.ai-threshold", config.get("aiThreshold").getAsDouble(),
                "neural.risk.ai-clear-threshold", config.get("aiClearThreshold").getAsDouble(),
                "neural.risk.ai-relief", config.get("aiRelief").getAsDouble(),
                "neural.risk.watch", config.get("watch").getAsDouble(),
                "neural.risk.suspicious", config.get("suspicious").getAsDouble(),
                "neural.risk.confirmed", config.get("confirmed").getAsDouble()));
        RiskEngine engine = new RiskEngine(NeuralConfig.read(NeuralConfigTest.config(settings)).risk());

        JsonArray predictions = fixture.getAsJsonArray("predictions");
        JsonArray steps = fixture.getAsJsonArray("steps");
        assertEquals(predictions.size(), steps.size(), "one step per prediction");

        long start = predictions.get(0).getAsJsonObject().get("nanoTime").getAsLong();
        PlayerRiskProfile profile = new PlayerRiskProfile(256, start);
        List<String> transitions = new ArrayList<>();
        for (int index = 0; index < predictions.size(); index++) {
            JsonObject expected = steps.get(index).getAsJsonObject();
            PredictionResult result = prediction(predictions.get(index).getAsJsonObject());
            long now = result.nanoTime();

            // NeuralRuntime applies exactly this: evidence when there is any, decay otherwise.
            JsonObject input = predictions.get(index).getAsJsonObject();
            double share = input.has("share") ? input.get("share").getAsDouble() : 1.0;
            Evidence evidence = engine.fromPrediction(result, share, now);
            if (evidence == null) {
                engine.decay(profile, now);
                assertTrue(expected.get("evidence").isJsonNull(),
                        "step " + index + ": Java found no evidence, the fixture expected "
                                + expected.get("evidence"));
            } else {
                assertFalse(expected.get("evidence").isJsonNull(),
                        "step " + index + ": Java produced " + evidence.type() + ", the fixture expected none");
                assertEquals(expected.get("evidence").getAsString(), evidence.type().name(), "step " + index + " type");
                assertEquals(expected.get("strength").getAsDouble(), evidence.strength(), TOLERANCE,
                        "step " + index + " strength");
                boolean transitioned = engine.accept(profile, evidence, now);
                assertEquals(expected.get("transitioned").getAsBoolean(), transitioned, "step " + index + " transition");
                if (transitioned) transitions.add(profile.state().name());
            }
            assertEquals(expected.get("risk").getAsDouble(), profile.risk(), TOLERANCE, "step " + index + " risk");
            assertEquals(expected.get("state").getAsString(), profile.state().name(), "step " + index + " state");
        }

        assertEquals(fixture.get("peakRisk").getAsDouble(), profile.peakRisk(), TOLERANCE);
        JsonObject counts = fixture.getAsJsonObject("counts");
        for (RiskState state : new RiskState[]{RiskState.WATCH, RiskState.SUSPICIOUS, RiskState.CONFIRMED}) {
            long expected = counts.get(state.name()).getAsLong();
            long seen = transitions.stream().filter(name -> name.equals(state.name())).count();
            assertEquals(expected, seen, "entries into " + state);
        }
    }

    @Test void theFixtureExercisesEveryEvidenceBranch() throws Exception {
        JsonArray steps = fixture("risk_golden.json").getAsJsonArray("steps");
        List<String> kinds = new ArrayList<>();
        for (JsonElement element : steps) {
            JsonElement evidence = element.getAsJsonObject().get("evidence");
            kinds.add(evidence.isJsonNull() ? "none" : evidence.getAsString());
        }
        for (String required : new String[]{"AI_AIM", "AI_KILLAURA", "AI_OVERALL", "AI_RELIEF", "none"}) {
            assertTrue(kinds.contains(required), "fixture no longer covers " + required);
        }
    }

    private static PredictionResult prediction(JsonObject json) {
        JsonObject heads = json.getAsJsonObject("heads");
        String[] names = new String[heads.entrySet().size()];
        double[] values = new double[names.length];
        int index = 0;
        for (Map.Entry<String, JsonElement> head : heads.entrySet()) {
            names[index] = head.getKey();
            values[index++] = head.getValue().getAsDouble();
        }
        double prior = json.has("prior") && !json.get("prior").isJsonNull() ? json.get("prior").getAsDouble() : Double.NaN;
        return new PredictionResult(index, json.get("nanoTime").getAsLong(), ModelKind.FLASH, "golden-v1",
                json.get("calibrated").getAsBoolean(), names, values, 0, prior);
    }

    private static JsonObject fixture(String name) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        assertNotNull(root, "repository root not found");
        Path path = root.resolve("ml/tests/data/" + name);
        assertTrue(Files.exists(path), "missing " + path + "; run python -m aeroml.tools.make_risk_golden");
        return new JsonParser().parse(Files.readString(path)).getAsJsonObject();
    }
}
