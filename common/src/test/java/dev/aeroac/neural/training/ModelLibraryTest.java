package dev.aeroac.neural.training;

import dev.aeroac.neural.inference.local.LocalModelBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModelLibraryTest {

    @Test void activationCopiesTheBundleKeepsTheOldOneAndRefusesSyntheticModels(@TempDir Path temp) throws Exception {
        Path dataset = temp.resolve("datasets");
        SyntheticRecordings.write(dataset, 10, 420, 3);
        Path models = temp.resolve("models");
        JavaTrainer.Settings settings = JavaTrainer.Settings.flash().withEpochs(1).withThreads(2).withData(true, false, false);
        new JavaTrainer(settings, null, null).run(dataset, ModelLibrary.trainedRoot(models).resolve("first"));
        Path active = models.resolve("flash");

        TrainingException refused = assertThrows(TrainingException.class, () -> ModelLibrary.activate(models, "first", active));
        assertTrue(refused.getMessage().contains("синтетике"));
        assertThrows(TrainingException.class, () -> ModelLibrary.activate(models, "../escape", active));

        // Pretend a real run produced it.
        Path manifest = ModelLibrary.trainedRoot(models).resolve("first/manifest.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"synthetic\": true", "\"synthetic\": false"));
        new JavaTrainer(settings.withSeed(5), null, null).run(dataset, ModelLibrary.trainedRoot(models).resolve("second"));
        Path second = ModelLibrary.trainedRoot(models).resolve("second/manifest.json");
        Files.writeString(second, Files.readString(second).replace("\"synthetic\": true", "\"synthetic\": false"));

        ModelLibrary.activate(models, "first", active);
        String firstVersion = LocalModelBundle.load(active).modelVersion();
        ModelLibrary.activate(models, "second", active);
        assertNotEquals(firstVersion, LocalModelBundle.load(active).modelVersion());
        assertTrue(Files.exists(models.resolve("previous").resolve(firstVersion).resolve("model.weights")), "previous model kept");
        assertEquals(2, ModelLibrary.list(models).size());
    }
}
