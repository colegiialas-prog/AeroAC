package dev.aeroac.neural.admin.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class HttpTrainingServiceClientTest {
    @Test
    void parsesTheNestedAcknowledgementReturnedByPostTrainingJobs() {
        JsonObject response = JsonParser.parseString("""
                {"jobId":"outside","state":"QUEUED","message":"accepted","job":{
                  "status":"QUEUED","javaStatus":"QUEUED","jobId":"job-42","modelType":"flash",
                  "datasetVersion":null,"featureSchemaVersion":3,"window":"attack",
                  "heads":["overall","aimAssist"],"epoch":0,"totalEpochs":3,"progress":0.0,
                  "trainLoss":null,"validationLoss":null,"elapsedSeconds":0,"updatedAtMillis":123,
                  "message":"queued"}}
                """).getAsJsonObject();

        TrainingJob job = HttpTrainingServiceClient.parseJob(response);

        assertEquals(TrainingJob.Status.QUEUED, job.status());
        assertEquals("job-42", job.jobId());
        assertEquals("overall, aimAssist", job.heads());
        assertTrue(job.running());
    }

    @Test
    void parsesEveryDetailedServiceStateAndRejectsUnknownStates() {
        JsonObject status = JsonParser.parseString("""
                {"status":"AUDITING","javaStatus":"QUEUED","jobId":"job-1","featureSchemaVersion":3,
                 "heads":[],"epoch":0,"totalEpochs":3,"progress":0.02,"elapsedSeconds":1,
                 "updatedAtMillis":123,"message":"audit"}
                """).getAsJsonObject();
        assertEquals(TrainingJob.Status.AUDITING, HttpTrainingServiceClient.parseJob(status).status());
        status.addProperty("status", "DEPLOYING");
        assertThrows(IllegalArgumentException.class, () -> HttpTrainingServiceClient.parseJob(status));
    }

    @Test
    void readsSessionReplayMetricsAndCohortFromTheEvaluationReport() {
        JsonObject result = JsonParser.parseString("""
                {"modelVersion":"candidate-1","datasetVersion":"dataset-v1","featureSchemaVersion":3,
                 "calibrated":true,"smokeOnly":false,"updatedAtMillis":456,
                 "evaluationReport":{"cohortId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "limitations":["held-out fixture"],
                  "windowMetrics":{"positives":100,"rocAuc":0.8,"prAuc":0.7,
                    "tprAtFpr":{"0.001":{"tpr":0.55,"reliable":1}}},
                  "riskSimulation":{
                    "falsePositives":{"perCombatHour":{"SUSPICIOUS":1.25}},
                    "detection":{"overall":{"medianTimeToSuspiciousSeconds":2.5},
                      "knownClient":{"sessions":2,"reachedSuspicious":0.5},
                      "unknownClient":{"sessions":1,"reachedSuspicious":0.0}},
                    "breakdowns":{"cheat":{"assistStrength":{"LOW":{"sessions":2,"reachedSuspicious":0.5}},
                      "clientFamily":{"client-a":{"sessions":1,"reachedSuspicious":1.0}},
                      "scenario":{"tracking":{"sessions":3,"reachedSuspicious":0.3333}}}}}}}
                """).getAsJsonObject();

        EvaluationSummary summary = HttpTrainingServiceClient.parseEvaluation(result);

        assertEquals(1.25, summary.falsePositivesPerCombatHour());
        assertEquals(2.5, summary.medianDetectionSeconds());
        assertEquals(0.55, summary.tprAtFpr());
        assertEquals("a".repeat(64), summary.cohortId());
        assertEquals(EvaluationSummary.Population.MIXED, summary.population());
        assertEquals(0.0, summary.detectionByPopulation().get("UNKNOWN_CLIENT"));
        assertEquals(0.5, summary.detectionByAssist().get("LOW"));
        assertTrue(summary.caveats().contains("held-out fixture"));
        assertFalse(summary.empty());
    }

    @Test
    void keepsMissingEvaluationMeasurementsMissing() {
        JsonObject result = JsonParser.parseString("""
                {"featureSchemaVersion":3,"calibrated":false,"updatedAtMillis":456,
                 "evaluationReport":{"cohortId":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                  "windowMetrics":{"tprAtFpr":{"0.001":{"reliable":0}}},
                  "riskSimulation":{"detection":{"knownClient":{"sessions":0},"unknownClient":{"sessions":0}}}}}
                """).getAsJsonObject();

        EvaluationSummary summary = HttpTrainingServiceClient.parseEvaluation(result);

        assertTrue(Double.isNaN(summary.tprAtFpr()));
        assertTrue(Double.isNaN(summary.falsePositivesPerCombatHour()));
        assertTrue(Double.isNaN(summary.medianDetectionSeconds()));
        assertTrue(Double.isNaN(summary.detectionByPopulation().get("KNOWN_CLIENT")));
    }

    @Test
    void doesNotPublishAnUnreliableTprWhenTheTestFoldHasNoPositives() {
        JsonObject result = JsonParser.parseString("""
                {"featureSchemaVersion":3,"updatedAtMillis":456,
                 "evaluationReport":{"cohortId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "windowMetrics":{"positives":0,"tprAtFpr":{"0.001":{"tpr":1.0,"reliable":1}}},
                  "riskSimulation":{"detection":{"knownClient":{"sessions":0},"unknownClient":{"sessions":0}}}}}
                """).getAsJsonObject();

        assertTrue(Double.isNaN(HttpTrainingServiceClient.parseEvaluation(result).tprAtFpr()));
    }

    @Test
    void rejectsInvalidExternalRatesAndCohortIdentity() {
        JsonObject invalidCohort = JsonParser.parseString("""
                {"featureSchemaVersion":3,"updatedAtMillis":456,
                 "evaluationReport":{"cohortId":"not-a-cohort",
                  "windowMetrics":{},"riskSimulation":{}}}
                """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> HttpTrainingServiceClient.parseEvaluation(invalidCohort));

        JsonObject invalidRate = JsonParser.parseString("""
                {"featureSchemaVersion":3,"updatedAtMillis":456,
                 "evaluationReport":{"cohortId":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "windowMetrics":{},"riskSimulation":{"detection":{"knownClient":{"sessions":1,
                  "reachedSuspicious":1.5},"unknownClient":{"sessions":0}}}}}
                """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> HttpTrainingServiceClient.parseEvaluation(invalidRate));
    }
}
