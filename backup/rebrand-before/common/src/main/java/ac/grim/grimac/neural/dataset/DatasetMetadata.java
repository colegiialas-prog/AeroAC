package ac.grim.grimac.neural.dataset;

import java.util.Locale;
import java.util.UUID;

/**
 * Everything known about a recording that is not telemetry.
 *
 * <p>{@code scenario} and {@code assistStrength} describe how the session was produced, so they are
 * exactly the kind of field a model must never read: they correlate with the label perfectly by
 * construction. They exist for grouping evaluation results and for planning what still needs to be
 * recorded. The model input contract lists both as forbidden, and the leakage audit enforces it.
 */
public record DatasetMetadata(UUID sessionId, String playerId, long startTimestamp, long startNanos,
                              Label label, LabelSource labelSource, String cheatFamily, String clientFamily,
                              String configuration, String scenario, AssistStrength assistStrength,
                              String notes, int minecraftProtocol, String pluginVersion,
                              int continuousSize, int attackBefore, int attackAfter) {
    public enum Label { LEGIT, CHEAT, UNLABELED }
    public enum LabelSource { LAB_LEGIT, LAB_CHEAT, PRODUCTION_UNLABELED }

    /**
     * How much help the cheat was configured to give. NONE is reserved for honest play; UNKNOWN is
     * the honest answer when the operator did not record it, and is never treated as NONE.
     */
    public enum AssistStrength {
        NONE, VERY_LOW, LOW, MEDIUM, HIGH, UNKNOWN;

        public static AssistStrength parse(String name) {
            if (name == null || name.isBlank()) return null;
            String cleaned = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (AssistStrength value : values()) if (value.name().equals(cleaned)) return value;
            return null;
        }

        public static String names() {
            StringBuilder text = new StringBuilder();
            for (AssistStrength value : values()) {
                if (text.length() > 0) text.append('|');
                text.append(value.name().toLowerCase(Locale.ROOT));
            }
            return text.toString();
        }
    }

    public DatasetMetadata {
        if (sessionId == null || playerId == null || label == null || labelSource == null) {
            throw new IllegalArgumentException("Missing session identity/label");
        }
        if (label == Label.CHEAT && (cheatFamily == null || cheatFamily.isBlank())) {
            throw new IllegalArgumentException("CHEAT requires a family");
        }
        if ((label == Label.LEGIT && labelSource != LabelSource.LAB_LEGIT)
                || (label == Label.CHEAT && labelSource != LabelSource.LAB_CHEAT)
                || (label == Label.UNLABELED && labelSource != LabelSource.PRODUCTION_UNLABELED)) {
            throw new IllegalArgumentException("Incompatible label source");
        }
        scenario = normalize(scenario);
        assistStrength = assistStrength == null ? defaultStrength(label) : assistStrength;
        if (label == Label.LEGIT && assistStrength != AssistStrength.NONE) {
            throw new IllegalArgumentException("LEGIT cannot carry an assist strength other than NONE");
        }
        if (label == Label.CHEAT && assistStrength == AssistStrength.NONE) {
            throw new IllegalArgumentException("CHEAT cannot be NONE; use " + AssistStrength.names()
                    + " or leave it unset for UNKNOWN");
        }
        if (length(cheatFamily) > 128 || length(clientFamily) > 128 || length(configuration) > 128
                || length(scenario) > 128 || length(notes) > 1024) {
            throw new IllegalArgumentException("Metadata too long");
        }
    }

    /** An unset strength is UNKNOWN for anything that is not honest play, never a quiet NONE. */
    private static AssistStrength defaultStrength(Label label) {
        return label == Label.LEGIT ? AssistStrength.NONE : AssistStrength.UNKNOWN;
    }

    /** Free-form but normalised, so "Box PvP" and "box-pvp" do not become two different groups. */
    private static String normalize(String scenario) {
        if (scenario == null) return null;
        String cleaned = scenario.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static int length(String text) { return text == null ? 0 : text.length(); }
}
