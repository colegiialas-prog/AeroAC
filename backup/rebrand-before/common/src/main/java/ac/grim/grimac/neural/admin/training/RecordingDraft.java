package ac.grim.grimac.neural.admin.training;

import ac.grim.grimac.neural.dataset.DatasetMetadata;

import java.util.Locale;
import java.util.UUID;

/**
 * What an operator has filled in so far in the start-recording wizard.
 *
 * <p>Mutable and per administrator, thrown away when the wizard closes. It exists so the interface
 * can refuse an impossible combination before a session is opened rather than after: the metadata
 * record itself enforces the same rules by throwing, and an exception surfacing as a chat line
 * halfway through a recording session is a much worse way to learn that LEGIT cannot carry an
 * assist strength.
 *
 * <p>This is not a second way to record. {@link #ready()} only decides whether the wizard may call
 * the one existing entry point; the semantics, the validation and the file that gets written are
 * the recorder's, unchanged.
 */
public final class RecordingDraft {
    private UUID targetUuid;
    private String targetName;
    private DatasetMetadata.Label label;
    private String cheatFamily = "";
    private String clientFamily = "";
    private String configuration = "";
    private DatasetMetadata.AssistStrength assistStrength;
    private String scenario = "";
    private String notes = "";

    public UUID targetUuid() { return targetUuid; }
    public String targetName() { return targetName; }
    public DatasetMetadata.Label label() { return label; }
    public String cheatFamily() { return cheatFamily; }
    public String clientFamily() { return clientFamily; }
    public String configuration() { return configuration; }
    public DatasetMetadata.AssistStrength assistStrength() { return assistStrength; }
    public String scenario() { return scenario; }
    public String notes() { return notes; }

    public RecordingDraft target(UUID uuid, String name) {
        this.targetUuid = uuid;
        this.targetName = name;
        return this;
    }

    /**
     * Choosing a label resets the fields that only make sense for one of them.
     *
     * <p>Without this an operator who picks CHEAT, sets HIGH, then changes their mind to LEGIT
     * would be holding a draft that can never be submitted, with no obvious way back.
     */
    public RecordingDraft label(DatasetMetadata.Label label) {
        this.label = label;
        if (label == DatasetMetadata.Label.LEGIT) {
            assistStrength = DatasetMetadata.AssistStrength.NONE;
            cheatFamily = "";
        } else if (label == DatasetMetadata.Label.UNLABELED) {
            assistStrength = DatasetMetadata.AssistStrength.UNKNOWN;
            cheatFamily = "";
        } else if (assistStrength == DatasetMetadata.AssistStrength.NONE) {
            assistStrength = null;
        }
        return this;
    }

    public RecordingDraft cheatFamily(String family) {
        this.cheatFamily = clean(family);
        return this;
    }

    public RecordingDraft clientFamily(String client) {
        this.clientFamily = clean(client);
        return this;
    }

    public RecordingDraft configuration(String configuration) {
        this.configuration = clean(configuration);
        return this;
    }

    public RecordingDraft assistStrength(DatasetMetadata.AssistStrength strength) {
        this.assistStrength = strength;
        return this;
    }

    public RecordingDraft scenario(String scenario) {
        this.scenario = clean(scenario).toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
        return this;
    }

    public RecordingDraft notes(String notes) {
        this.notes = clean(notes);
        return this;
    }

    /** Human-readable reason this draft cannot be submitted, or null when it can. */
    public String problem() {
        if (targetUuid == null) return "Pick a player to record.";
        if (label == null) return "Pick a label: LEGIT, CHEAT or UNLABELED.";
        if (label == DatasetMetadata.Label.CHEAT && cheatFamily.isEmpty()) {
            return "CHEAT needs a cheat family, so the session can be grouped later.";
        }
        if (label == DatasetMetadata.Label.LEGIT && assistStrength != DatasetMetadata.AssistStrength.NONE) {
            return "LEGIT is honest play: its assist strength is always NONE.";
        }
        if (label == DatasetMetadata.Label.CHEAT && assistStrength == DatasetMetadata.AssistStrength.NONE) {
            return "CHEAT cannot be NONE. Pick the strength the cheat was set to, or UNKNOWN if it was not recorded.";
        }
        if (cheatFamily.length() > 128 || clientFamily.length() > 128 || configuration.length() > 128
                || scenario.length() > 128) {
            return "One of the metadata fields is longer than 128 characters.";
        }
        if (notes.length() > 1024) return "Notes are longer than 1024 characters.";
        return null;
    }

    public boolean ready() { return problem() == null; }

    /** A confirmation owns its values even if the operator later edits their draft. */
    public RecordingDraft copy() {
        return new RecordingDraft().target(targetUuid, targetName).label(label).cheatFamily(cheatFamily)
                .clientFamily(clientFamily).configuration(configuration).assistStrength(assistStrength)
                .scenario(scenario).notes(notes);
    }

    /**
     * The strength as the recorder should receive it.
     *
     * <p>An unset strength on a CHEAT session is passed through as UNKNOWN rather than guessed:
     * "we did not write it down" and "the cheat was giving no help" are different facts, and the
     * second one would be a lie that later evaluation would take at face value.
     */
    public String assistArgument() {
        if (assistStrength == null) {
            return label == DatasetMetadata.Label.LEGIT
                    ? DatasetMetadata.AssistStrength.NONE.name()
                    : DatasetMetadata.AssistStrength.UNKNOWN.name();
        }
        return assistStrength.name();
    }

    /** Strengths the operator may choose for the label currently selected. */
    public DatasetMetadata.AssistStrength[] selectableStrengths() {
        if (label == DatasetMetadata.Label.LEGIT) {
            return new DatasetMetadata.AssistStrength[]{DatasetMetadata.AssistStrength.NONE};
        }
        return new DatasetMetadata.AssistStrength[]{
                DatasetMetadata.AssistStrength.VERY_LOW, DatasetMetadata.AssistStrength.LOW,
                DatasetMetadata.AssistStrength.MEDIUM, DatasetMetadata.AssistStrength.HIGH,
                DatasetMetadata.AssistStrength.UNKNOWN};
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
