package dev.aeroac.neural.training;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aeroac.neural.inference.local.LocalModelBundle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The models a server has trained (plugins/AeroAC/models/trained/&lt;name&gt;) and the one it runs
 * (the configured flash-bundle folder). Activating copies a trained bundle into the running folder,
 * keeping the previous one under models/previous/, so switching back is one more activation.
 */
public final class ModelLibrary {
    /** What a screen or a command shows about one bundle. NaN where nothing was measured. */
    public record ModelInfo(String name, Path path, String modelVersion, String kind, boolean synthetic,
                            boolean calibrated, double testRocAuc, double testPrAuc, double testTprAtFpr,
                            boolean unknownClientTest, String created, List<String> warnings) { }

    private static final List<String> FILES = List.of("manifest.json", "weights.json", "model.weights",
            "dataset_audit.json", "split_manifest.json");

    private ModelLibrary() { }

    public static Path trainedRoot(Path modelsRoot) { return modelsRoot.resolve("trained"); }

    /** A folder name for a new run: {@code flash-20261005-171500}. */
    public static String newName(String preset) {
        return preset + "-" + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /** Trained bundles, newest first. Folders without a readable manifest are skipped. */
    public static List<ModelInfo> list(Path modelsRoot) {
        Path root = trainedRoot(modelsRoot);
        List<ModelInfo> models = new ArrayList<>();
        if (!Files.isDirectory(root)) return models;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path path : stream.filter(Files::isDirectory).toList()) {
                ModelInfo info = read(path);
                if (info != null) models.add(info);
            }
        } catch (IOException ignored) {
            return models;
        }
        models.sort(Comparator.comparing(ModelInfo::created, Comparator.nullsLast(Comparator.reverseOrder())));
        return models;
    }

    /** The summary of one bundle folder, or null when it is not a bundle. */
    public static ModelInfo read(Path path) {
        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(path.resolve("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject provenance = object(manifest, "provenance");
            JsonObject evaluation = object(provenance, "evaluation");
            JsonObject test = object(object(evaluation, "folds"), "test");
            double tpr = Double.NaN;
            JsonObject tprs = object(test, "tprAtFpr");
            if (tprs != null && tprs.has("0.001")) tpr = number(tprs.getAsJsonObject("0.001"), "tpr");
            List<String> warnings = new ArrayList<>();
            if (evaluation != null && evaluation.has("warnings")) {
                for (JsonElement warning : evaluation.getAsJsonArray("warnings")) warnings.add(warning.getAsString());
            }
            JsonElement calibration = manifest.get("calibration");
            return new ModelInfo(path.getFileName().toString(), path, text(manifest, "modelVersion"),
                    text(manifest, "modelKind"), evaluation != null && evaluation.has("synthetic") && evaluation.get("synthetic").getAsBoolean(),
                    calibration != null && calibration.isJsonObject(), number(test, "rocAuc"), number(test, "prAuc"), tpr,
                    evaluation != null && "held-out".equals(text(evaluation, "unknownClientBenchmark")),
                    text(provenance, "created"), List.copyOf(warnings));
        } catch (IOException | RuntimeException notABundle) {
            return null;
        }
    }

    /**
     * Copies a trained bundle into {@code active}, after checking that this build loads it. The
     * folder it replaces is moved to models/previous/&lt;version&gt;. Synthetic smoke-test models are
     * refused: they say nothing about real players.
     *
     * @return the activated model's summary
     */
    public static ModelInfo activate(Path modelsRoot, String name, Path active) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || name.contains("..")) {
            throw new TrainingException("Недопустимое имя модели: " + name);
        }
        Path source = trainedRoot(modelsRoot).resolve(name);
        ModelInfo info = read(source);
        if (info == null) throw new TrainingException("Модель " + name + " не найдена в " + trainedRoot(modelsRoot));
        if (info.synthetic()) throw new TrainingException("Модель " + name + " обучена на синтетике (проверочный прогон); на сервер её не ставят.");
        LocalModelBundle bundle;
        try {
            bundle = LocalModelBundle.load(source);
        } catch (IOException | RuntimeException error) {
            throw new TrainingException("Модель " + name + " не загружается этой версией плагина: " + error.getMessage());
        }
        if (!"flash".equalsIgnoreCase(info.kind())) throw new TrainingException("Включать можно модель flash; эта — " + info.kind());
        Files.createDirectories(active.toAbsolutePath().getParent());
        if (Files.isDirectory(active) && Files.exists(active.resolve("manifest.json"))) {
            ModelInfo previous = read(active);
            String label = previous == null || previous.modelVersion() == null ? newName("previous") : previous.modelVersion();
            Path backup = modelsRoot.resolve("previous").resolve(label.replaceAll("[^A-Za-z0-9_.-]", "_"));
            if (Files.exists(backup)) backup = backup.resolveSibling(backup.getFileName() + "-" + System.currentTimeMillis());
            Files.createDirectories(backup);
            for (String file : FILES) {
                Path from = active.resolve(file);
                if (Files.exists(from)) Files.move(from, backup.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.createDirectories(active);
        // Weights first, manifest last: a reader that races the copy sees no manifest rather than a mismatched one.
        for (String file : List.of("model.weights", "weights.json", "dataset_audit.json", "split_manifest.json", "manifest.json")) {
            Path from = source.resolve(file);
            if (Files.exists(from)) Files.copy(from, active.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!bundle.modelVersion().equals(LocalModelBundle.load(active).modelVersion())) {
            throw new TrainingException("Копия модели не совпала с оригиналом; проверьте " + active);
        }
        return info;
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String text(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static double number(JsonObject parent, String key) {
        if (parent == null) return Double.NaN;
        JsonElement value = parent.get(key);
        return value == null || value.isJsonNull() ? Double.NaN : value.getAsDouble();
    }
}
