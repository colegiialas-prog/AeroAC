package dev.aeroac.neural.training;

import dev.aeroac.neural.inference.local.LocalModelBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End to end: recordings in the plugin's layout in, a bundle the local runtime loads out. */
class JavaTrainerTest {

    @Test void trainsABundleTheLocalRuntimeLoadsAndThatSeparatesTheClasses(@TempDir Path temp) throws Exception {
        Path dataset = temp.resolve("datasets");
        SyntheticRecordings.write(dataset, 10, 420, 11);
        List<String> stages = new ArrayList<>();
        JavaTrainer.Settings settings = JavaTrainer.Settings.flash().withEpochs(4).withThreads(4).withData(true, false, false);
        JavaTrainer.Result result = new JavaTrainer(settings,
                (stage, epoch, total, loss, validation, message) -> stages.add(stage), null)
                .run(dataset, temp.resolve("bundle"));

        assertTrue(result.synthetic());
        assertTrue(stages.containsAll(List.of("auditing", "preparing", "training", "calibrating", "evaluating", "exporting", "completed")));
        LocalModelBundle bundle = LocalModelBundle.load(result.bundle());
        assertEquals(List.of("overall", "aimAssist"), bundle.heads());
        assertEquals(31, bundle.sequenceLength());
        assertEquals(result.modelVersion(), bundle.modelVersion());
        @SuppressWarnings("unchecked")
        Map<String, Object> test = (Map<String, Object>) result.evaluation().get("test");
        assertTrue(((Number) test.get("rocAuc")).doubleValue() > 0.9, "test ROC-AUC " + test.get("rocAuc"));
        for (String file : List.of("manifest.json", "weights.json", "model.weights", "dataset_audit.json", "split_manifest.json")) {
            assertTrue(Files.isRegularFile(result.bundle().resolve(file)), file);
        }
    }

    @Test void refusesSyntheticDataUnlessAskedAndNeverOverwritesABundle(@TempDir Path temp) throws Exception {
        Path dataset = temp.resolve("datasets");
        SyntheticRecordings.write(dataset, 6, 420, 5);
        JavaTrainer.Settings settings = JavaTrainer.Settings.flash().withEpochs(1).withThreads(2);
        TrainingException synthetic = assertThrows(TrainingException.class,
                () -> new JavaTrainer(settings, null, null).run(dataset, temp.resolve("a")));
        assertTrue(synthetic.getMessage().contains("синтетические"), synthetic.getMessage());
        Files.createDirectories(temp.resolve("b"));
        Files.writeString(temp.resolve("b/manifest.json"), "{}");
        assertThrows(TrainingException.class,
                () -> new JavaTrainer(settings.withData(true, false, false), null, null).run(dataset, temp.resolve("b")));
    }

    @Test void theSameSeedGivesTheSameModelWhateverTheThreadCount(@TempDir Path temp) throws Exception {
        Path dataset = temp.resolve("datasets");
        SyntheticRecordings.write(dataset, 10, 300, 9);
        JavaTrainer.Settings settings = JavaTrainer.Settings.flash().withEpochs(1).withData(true, false, false);
        new JavaTrainer(settings.withThreads(1), null, null).run(dataset, temp.resolve("one"));
        new JavaTrainer(settings.withThreads(3), null, null).run(dataset, temp.resolve("three"));
        byte[] one = Files.readAllBytes(temp.resolve("one/model.weights"));
        byte[] three = Files.readAllBytes(temp.resolve("three/model.weights"));
        // Summation order differs across thread counts, so weights agree closely rather than bit for bit.
        java.nio.FloatBuffer a = java.nio.ByteBuffer.wrap(one).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        java.nio.FloatBuffer b = java.nio.ByteBuffer.wrap(three).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        assertEquals(a.remaining(), b.remaining());
        double worst = 0;
        for (int i = 0; i < a.remaining(); i++) worst = Math.max(worst, Math.abs(a.get(i) - b.get(i)));
        assertTrue(worst < 1e-3, "largest weight difference " + worst);
    }
}
