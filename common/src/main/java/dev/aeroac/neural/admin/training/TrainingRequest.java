package dev.aeroac.neural.admin.training;

import java.util.List;

/** Wire contract only: dataset is a server-side name, never a filesystem path. */
public record TrainingRequest(String dataset, String preset, String window, List<String> heads,
                              int featureSchemaVersion, long seed) {
    public TrainingRequest {
        if (dataset == null || !dataset.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") || dataset.contains(".."))
            throw new IllegalArgumentException("Недопустимое имя датасета.");
        if (!("flash".equals(preset) && "attack".equals(window))
                && !("pro".equals(preset) && "continuous".equals(window)))
            throw new IllegalArgumentException("Тип модели не соответствует окну.");
        if (featureSchemaVersion != 2) throw new IllegalArgumentException("Ожидается схема признаков v2.");
        heads = List.copyOf(heads);
        if (heads.isEmpty() || !heads.contains("overall") || heads.stream().distinct().count() != heads.size())
            throw new IllegalArgumentException("Укажите уникальные выходы модели, включая overall.");
    }
}
