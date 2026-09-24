package dev.aeroac.neural.admin;

import dev.aeroac.neural.admin.training.CoverageReport;
import dev.aeroac.neural.admin.training.DatasetSummary;
import dev.aeroac.neural.admin.training.DatasetSummaryService;
import dev.aeroac.neural.admin.training.EvaluationSummary;
import dev.aeroac.neural.admin.training.ModelComparison;
import dev.aeroac.neural.admin.training.RecordingDraft;
import dev.aeroac.neural.admin.training.TrainingJob;
import dev.aeroac.neural.admin.training.TrainingServiceClient;
import dev.aeroac.neural.dataset.DatasetMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The training centre's rules: what a wizard may submit, what a runtime may claim about a session,
 * and what happens when nobody has trained or evaluated anything.
 */
class TrainingCenterTest {

    // ---- recording wizard validation -------------------------------------------------------

    @Test void legitIsAlwaysNoneAndCheatIsNever() {
        RecordingDraft legit = new RecordingDraft()
                .target(UUID.randomUUID(), "Tester")
                .label(DatasetMetadata.Label.LEGIT);
        assertEquals(DatasetMetadata.AssistStrength.NONE, legit.assistStrength());
        assertNull(legit.problem());
        assertArrayEquals(new DatasetMetadata.AssistStrength[]{DatasetMetadata.AssistStrength.NONE},
                legit.selectableStrengths());

        legit.assistStrength(DatasetMetadata.AssistStrength.HIGH);
        assertNotNull(legit.problem(), "LEGIT carrying an assist strength must be refused");

        RecordingDraft cheat = new RecordingDraft()
                .target(UUID.randomUUID(), "Tester")
                .label(DatasetMetadata.Label.CHEAT);
        assertNotNull(cheat.problem(), "CHEAT without a family must be refused");
        cheat.cheatFamily("aim-assist");
        cheat.assistStrength(DatasetMetadata.AssistStrength.NONE);
        assertNotNull(cheat.problem(), "CHEAT can never be NONE");
        for (DatasetMetadata.AssistStrength offered : cheat.selectableStrengths()) {
            assertNotEquals(DatasetMetadata.AssistStrength.NONE, offered,
                    "the wizard must not offer NONE for a CHEAT session");
        }
    }

    @Test void anUnsetStrengthOnCheatIsUnknownNeverNone() {
        RecordingDraft cheat = new RecordingDraft()
                .target(UUID.randomUUID(), "Tester")
                .label(DatasetMetadata.Label.CHEAT)
                .cheatFamily("kill-aura");
        assertEquals("UNKNOWN", cheat.assistArgument());
        assertNull(cheat.problem());

        RecordingDraft legit = new RecordingDraft()
                .target(UUID.randomUUID(), "Tester")
                .label(DatasetMetadata.Label.LEGIT);
        assertEquals("NONE", legit.assistArgument());
    }

    @Test void changingTheLabelClearsFieldsThatCannotApply() {
        RecordingDraft draft = new RecordingDraft()
                .target(UUID.randomUUID(), "Tester")
                .label(DatasetMetadata.Label.CHEAT)
                .cheatFamily("aim-assist");
        draft.assistStrength(DatasetMetadata.AssistStrength.HIGH);
        draft.label(DatasetMetadata.Label.LEGIT);
        assertEquals("", draft.cheatFamily());
        assertEquals(DatasetMetadata.AssistStrength.NONE, draft.assistStrength());
        assertNull(draft.problem(), "switching to LEGIT must leave a submittable draft");
    }

    @Test void theDraftProducesMetadataTheRecorderAccepts() {
        RecordingDraft draft = new RecordingDraft()
                .target(UUID.randomUUID(), "Tester")
                .label(DatasetMetadata.Label.CHEAT)
                .cheatFamily("Aim Assist")
                .clientFamily("example-client")
                .configuration("smooth-30")
                .scenario("Box PvP");
        draft.assistStrength(DatasetMetadata.AssistStrength.VERY_LOW);
        assertTrue(draft.ready());
        assertEquals("box-pvp", draft.scenario(), "scenarios are normalised the way the recorder does");

        DatasetMetadata metadata = new DatasetMetadata(UUID.randomUUID(), "pseudonym", 1, 1,
                draft.label(), DatasetMetadata.LabelSource.LAB_CHEAT, draft.cheatFamily(),
                draft.clientFamily(), draft.configuration(), draft.scenario(),
                DatasetMetadata.AssistStrength.parse(draft.assistArgument()), "", 47, "test",
                96, 20, 10);
        assertEquals(DatasetMetadata.AssistStrength.VERY_LOW, metadata.assistStrength());
        assertEquals("box-pvp", metadata.scenario());
    }

    @Test void overlongMetadataIsRefusedBeforeASessionOpens() {
        RecordingDraft draft = new RecordingDraft()
                .target(UUID.randomUUID(), "Tester")
                .label(DatasetMetadata.Label.LEGIT)
                .clientFamily("x".repeat(200));
        assertNotNull(draft.problem());
    }

    // ---- live recording rendering ----------------------------------------------------------

    private static RecordingView recording(long seconds, long windows, long dropped, double targetKnown) {
        return new RecordingView(UUID.randomUUID(), "Tester", UUID.randomUUID(),
                RecordingView.State.RECORDING, DatasetMetadata.Label.CHEAT, "aim-assist", "example",
                "smooth", DatasetMetadata.AssistStrength.LOW, "box-pvp",
                seconds, 100, 120, 30, 45, windows, dropped, 0, null, null, targetKnown, 0.9, 0.9);
    }

    @Test void progressIsAFractionOfTheOperatorsOwnTarget() {
        RecordingView view = recording(222, 126, 0, 0.9);
        assertEquals(222 / 300.0, view.durationProgress(300), 1e-9);
        assertEquals(126 / 150.0, view.windowProgress(150), 1e-9);
        assertEquals(1.0, recording(9999, 9999, 0, 0.9).durationProgress(300), 1e-9,
                "progress is clamped rather than showing 3000%");
        assertEquals(0.0, view.durationProgress(0), 1e-9);
    }

    @Test void qualityWarningsFlagCountersNotVerdicts() {
        assertFalse(recording(222, 126, 0, 0.9).qualityWarning());
        assertTrue(recording(222, 126, 3, 0.9).qualityWarning(), "dropped records are always worth a look");
        assertTrue(recording(300, 0, 0, 0.9).qualityWarning(), "a long fight with no buildable window");
        assertTrue(recording(300, 40, 0, 0.2).qualityWarning(), "most frames carrying no target");
        assertFalse(recording(10, 0, 0, 0.0).qualityWarning(), "a session that just started is not a problem");
    }

    @Test void theShortLabelCarriesFamilyAndStrength() {
        assertEquals("ЧИТ AIM-ASSIST НИЗКАЯ", recording(1, 1, 0, 1).shortLabel());
        RecordingView legit = new RecordingView(UUID.randomUUID(), "T", UUID.randomUUID(),
                RecordingView.State.RECORDING, DatasetMetadata.Label.LEGIT, "", "", "",
                DatasetMetadata.AssistStrength.NONE, "flat-duel", 10, 1, 1, 1, 1, 1, 0, 0, null, null,
                1, 1, 1);
        assertEquals("ЧЕСТНАЯ ИГРА", legit.shortLabel());
    }

    // ---- dataset summary -------------------------------------------------------------------

    private static void writeSession(Path metadata, String id, String label, String family,
                                     String client, String assist, String scenario,
                                     boolean complete, int dropped) throws Exception {
        String json = "{\n"
                + "  \"sessionId\": \"" + id + "\",\n"
                + "  \"playerId\": \"player-" + id + "\",\n"
                + "  \"startTimestamp\": " + (1000 + id.hashCode() % 100) + ",\n"
                + "  \"label\": \"" + label + "\",\n"
                + "  \"labelSource\": \"LAB_" + label + "\",\n"
                + "  \"cheatFamily\": \"" + family + "\",\n"
                + "  \"clientFamily\": \"" + client + "\",\n"
                + "  \"configuration\": \"default\",\n"
                + "  \"scenario\": \"" + scenario + "\",\n"
                + "  \"assistStrength\": \"" + assist + "\",\n"
                + "  \"minecraftProtocol\": 47,\n"
                + "  \"durationMs\": 60000,\n"
                + "  \"frames\": 1200,\n"
                + "  \"records\": 1300,\n"
                + "  \"droppedRecords\": " + dropped + ",\n"
                + "  \"complete\": " + complete + ",\n"
                + "  \"closeReason\": \"MANUAL_STOP\",\n"
                + "  \"failure\": null\n"
                + "}\n";
        Files.writeString(metadata.resolve("session-" + id + ".json"), json, StandardCharsets.UTF_8);
    }

    private static DatasetSummary load(Path root) throws Exception {
        try (DatasetSummaryService service = new DatasetSummaryService(root, 60, 5000, 45)) {
            DatasetSummary summary = service.snapshot();
            for (int i = 0; i < 100 && !summary.ready(); i++) {
                Thread.sleep(20);
                summary = service.snapshot();
            }
            return summary;
        }
    }

    @Test void summarisesMetadataWithoutTouchingTelemetry(@TempDir Path root) throws Exception {
        Path metadata = Files.createDirectories(root.resolve("metadata"));
        Files.createDirectories(root.resolve("raw"));
        // A raw file that must never be read: if the summary opened it, the test would be slow and
        // the counts would come from somewhere this interface is not allowed to look.
        Files.writeString(root.resolve("raw").resolve("session-a.jsonl"), "not json at all\n");

        writeSession(metadata, "a", "CHEAT", "aim-assist", "alpha", "VERY_LOW", "box-pvp", true, 0);
        writeSession(metadata, "b", "CHEAT", "aim-assist", "beta", "LOW", "tracking", true, 0);
        writeSession(metadata, "c", "LEGIT", "", "vanilla", "NONE", "flat-duel", true, 0);
        writeSession(metadata, "d", "LEGIT", "", "vanilla", "NONE", "flat-duel", false, 4);

        DatasetSummary summary = load(root);
        assertTrue(summary.ready());
        assertNull(summary.error());
        assertEquals(4, summary.sessions());
        assertEquals(4, summary.players());
        assertEquals(2, summary.cheat());
        assertEquals(2, summary.legit());
        assertEquals(1, summary.incomplete());
        assertEquals(1, summary.withDropped());
        assertEquals(2, summary.cheatFamilies().get("aim-assist"));
        assertEquals(2, summary.distinctCheatClients());
        assertEquals(1, summary.assistCount("VERY_LOW"));
        assertEquals(1, summary.assistCount("LOW"));
        assertEquals(0, summary.assistCount("HIGH"));
        assertEquals(2, summary.legitScenarios().get("flat-duel"));
        assertEquals(0, summary.audited(), "no audit report was imported");
        assertEquals(0, summary.reviewed(), "nothing was human reviewed");
        assertEquals(1, summary.attention().size(),
                "one session is both incomplete and dropped records; it is one row, not two");
    }

    @Test void unboundReportsAndUnverifiedManifestEntriesAreNotImported(@TempDir Path root) throws Exception {
        Path metadata = Files.createDirectories(root.resolve("metadata"));
        writeSession(metadata, "a", "CHEAT", "aim-assist", "alpha", "LOW", "box-pvp", true, 0);
        writeSession(metadata, "b", "LEGIT", "", "vanilla", "NONE", "flat-duel", true, 0);

        assertEquals(0, load(root).audited());

        Files.createDirectories(root.resolve("audit"));
        Files.writeString(root.resolve("audit").resolve("latest.json"),
                "{\"sessions\":[{\"sessionId\":\"a\",\"verdict\":\"REVIEW\",\"reasons\":[\"tick holes: 4\"]},"
                        + "{\"sessionId\":\"b\",\"verdict\":\"GOOD\",\"reasons\":[]}]}");
        Files.createDirectories(root.resolve("manifests"));
        Files.writeString(root.resolve("manifests").resolve("golden-v1.json"),
                "{\"manifestVersion\":1,\"name\":\"golden\",\"reviewStatus\":\"HUMAN_REVIEWED\","
                        + "\"sessions\":[{\"sessionId\":\"b\",\"expectedLabel\":\"LEGIT\",\"sha256\":\"abc\","
                        + "\"reviewer\":\"operator\",\"reviewedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"reviewNotes\":\"looked fine\"}]}");

        DatasetSummary summary = load(root);
        assertEquals(0, summary.audited(), "an old report with no file identities is not a current audit");
        assertEquals(0, summary.reviewed(), "a reviewer string alone is not a verified review");
        assertTrue(summary.attention().isEmpty());
    }

    @Test void anUnreadableDatasetDirectoryIsReportedNotGuessed(@TempDir Path root) throws Exception {
        DatasetSummary summary = load(root);
        assertTrue(summary.ready());
        assertEquals(0, summary.sessions());
        assertNull(summary.error(), "a dataset that does not exist yet is empty, not broken");
    }

    @Test void aCachedSummaryIsServedWithoutReadingAgain(@TempDir Path root) throws Exception {
        Path metadata = Files.createDirectories(root.resolve("metadata"));
        writeSession(metadata, "a", "LEGIT", "", "vanilla", "NONE", "flat-duel", true, 0);
        try (DatasetSummaryService service = new DatasetSummaryService(root, 3600, 5000, 45)) {
            DatasetSummary first = service.snapshot();
            for (int i = 0; i < 100 && !first.ready(); i++) {
                Thread.sleep(20);
                first = service.snapshot();
            }
            assertEquals(1, first.sessions());

            writeSession(metadata, "b", "LEGIT", "", "vanilla", "NONE", "flat-duel", true, 0);
            Thread.sleep(60);
            assertEquals(1, service.snapshot().sessions(), "a fresh cache must not re-read the directory");

            service.invalidate();
            DatasetSummary second = service.snapshot();
            for (int i = 0; i < 100 && second.sessions() != 2; i++) {
                Thread.sleep(20);
                second = service.snapshot();
            }
            assertEquals(2, second.sessions());
        }
    }

    // ---- coverage --------------------------------------------------------------------------

    @Test void coverageReportsShortfallsWithoutClaimingSufficiency() {
        DatasetSummary summary = new DatasetSummary(45, 9, 20, 25, 0, 0, 0, 0, 0, 0,
                0, 0, 0, Map.of(), Map.of("alpha", 20, "beta", 5), Map.of(),
                Map.of("VERY_LOW", 4, "LOW", 17, "MEDIUM", 21, "HIGH", 20, "UNKNOWN", 3),
                Map.of(), Map.of("flat-duel", 20), Map.of(), Map.of(),
                List.of(), List.of(), false, 1, null);
        AdminConfig.Training goals = new AdminConfig.Training(300, 150,
                List.of("box-pvp", "flat-duel"), List.of(), List.of(), List.of(), 20, 5, 20);

        CoverageReport report = CoverageReport.of(summary, goals);
        assertTrue(report.anyShort());
        CoverageReport.Row veryLow = report.rows().stream()
                .filter(row -> row.label().equals("VERY_LOW")).findFirst().orElseThrow();
        assertTrue(veryLow.shortfall());
        assertEquals(4, veryLow.count());

        CoverageReport.Row medium = report.rows().stream()
                .filter(row -> row.label().equals("MEDIUM")).findFirst().orElseThrow();
        assertFalse(medium.shortfall());

        CoverageReport.Row unknown = report.rows().stream()
                .filter(row -> row.label().equals("UNKNOWN")).findFirst().orElseThrow();
        assertEquals(0, unknown.goal(), "nobody should be asked to collect more unrecorded strengths");
        assertFalse(unknown.shortfall());

        CoverageReport.Row clients = report.rows().stream()
                .filter(row -> row.group().equals(CoverageReport.GROUP_CLIENTS)).findFirst().orElseThrow();
        assertTrue(clients.shortfall(), "two client families against a goal of five is a shortfall");
    }

    // ---- training service ------------------------------------------------------------------

    @Test void withoutATrainingServiceEverythingSaysSo() {
        TrainingServiceClient offline = new TrainingServiceClient.Offline();
        assertFalse(offline.configured());
        assertEquals(TrainingJob.Status.NOT_CONFIGURED, offline.job().status());
        assertFalse(offline.job().configured());
        assertFalse(offline.job().running());
        assertNotNull(offline.unavailableReason());
        assertFalse(offline.currentEvaluation().present());
        assertFalse(offline.candidateEvaluation().present());
        offline.poll();   // must not throw and must not block
        offline.close();
    }

    /**
     * The status enum has no deployed state.
     *
     * <p>This is the test that stops a future change from quietly adding one: promotion is a human
     * decision taken elsewhere, and a status this plugin could set would be the first step towards
     * a model swapping itself in because a number improved.
     */
    @Test void thereIsNoDeployedStatus() {
        for (TrainingJob.Status status : TrainingJob.Status.values()) {
            String name = status.name();
            assertFalse(name.contains("DEPLOY"), name);
            assertFalse(name.contains("PROMOT"), name);
            assertFalse(name.contains("ACTIVE"), name);
            assertFalse(name.contains("LIVE"), name);
        }
        assertEquals(TrainingJob.Status.CANDIDATE,
                TrainingJob.Status.values()[TrainingJob.Status.values().length - 2],
                "CANDIDATE stays the last successful state before FAILED");
    }

    @Test void missingMetricsStayMissing() {
        assertFalse(EvaluationSummary.NONE.present());
        assertTrue(EvaluationSummary.NONE.empty());
        assertTrue(Double.isNaN(EvaluationSummary.NONE.rocAuc()));
        assertTrue(Double.isNaN(EvaluationSummary.NONE.falsePositivesPerCombatHour()));
        assertEquals(EvaluationSummary.Calibration.UNKNOWN, EvaluationSummary.NONE.calibration());
    }

    @Test void comparisonMarksRegressionsPerMetricDirection() {
        EvaluationSummary current = new EvaluationSummary("v1", "dataset-v1", 2,
                0.90, 0.80, 0.001, 0.60, 0.20, 12.0,
                EvaluationSummary.Calibration.CALIBRATED, EvaluationSummary.Population.KNOWN_CLIENT,
                Map.of("VERY_LOW", 0.30, "LOW", 0.50), Map.of(), Map.of(), List.of(), 1, "a".repeat(64), Map.of());
        EvaluationSummary candidate = new EvaluationSummary("v2", "dataset-v1", 2,
                0.93, 0.84, 0.001, 0.72, 0.35, 9.0,
                EvaluationSummary.Calibration.CALIBRATED, EvaluationSummary.Population.KNOWN_CLIENT,
                Map.of("VERY_LOW", 0.25, "LOW", 0.61), Map.of(), Map.of(), List.of(), 2, "a".repeat(64), Map.of());

        ModelComparison comparison = ModelComparison.of(current, candidate);
        assertTrue(comparison.present());
        assertFalse(comparison.unmeasured());

        ModelComparison.Row falsePositives = row(comparison, ModelComparison.METRIC_FALSE_POSITIVES);
        assertTrue(falsePositives.regression(), "more false positives per hour is a regression");

        ModelComparison.Row detection = row(comparison, ModelComparison.METRIC_DETECTION_AT_FPR);
        assertTrue(detection.improvement());

        ModelComparison.Row veryLow = row(comparison, ModelComparison.METRIC_DETECTION_PREFIX + "VERY_LOW");
        assertTrue(veryLow.regression(), "catching fewer of the subtlest cheats is a regression");

        ModelComparison.Row detectionTime = row(comparison, ModelComparison.METRIC_DETECTION_TIME);
        assertTrue(detectionTime.improvement(), "detecting sooner is better");

        assertTrue(comparison.anyRegression());
    }

    @Test void comparisonWithNothingMeasuredIsUnmeasurableNotZero() {
        ModelComparison comparison = ModelComparison.of(EvaluationSummary.NONE, EvaluationSummary.NONE);
        assertTrue(comparison.unmeasured());
        assertFalse(comparison.anyRegression());
        for (ModelComparison.Row row : comparison.rows()) {
            assertFalse(row.measured());
            assertFalse(row.regression());
            assertFalse(row.improvement());
            assertTrue(Double.isNaN(row.delta()));
        }
    }

    private static ModelComparison.Row row(ModelComparison comparison, String metric) {
        return comparison.rows().stream().filter(row -> row.metric().equals(metric))
                .findFirst().orElseThrow();
    }
}
