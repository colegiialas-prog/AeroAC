package dev.aeroac.neural.admin;

import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.neural.dataset.DatasetSession;

import java.util.UUID;

/**
 * One live dataset recording, as an operator sees it.
 *
 * <p>Every number is read from the session the recorder already maintains. The runtime deliberately
 * publishes no verdict: {@link #qualityWarning()} says a counter looks wrong, which is not the same
 * claim as GOOD, REVIEW or UNUSABLE. Those come from the offline audit and nowhere else.
 */
public record RecordingView(UUID sessionId, String playerName, UUID playerUuid, State state,
                            DatasetMetadata.Label label, String cheatFamily, String clientFamily,
                            String configuration, DatasetMetadata.AssistStrength assistStrength, String scenario,
                            long durationSeconds, long frames, long records, long attacks, long swings,
                            long attackWindows, long dropped, int queued, String closeReason, String failure,
                            double targetKnown, double aimErrorKnown, double geometryKnown, long movementGaps) {

    public RecordingView(UUID sessionId, String playerName, UUID playerUuid, State state,
                         DatasetMetadata.Label label, String cheatFamily, String clientFamily,
                         String configuration, DatasetMetadata.AssistStrength assistStrength, String scenario,
                         long durationSeconds, long frames, long records, long attacks, long swings,
                         long attackWindows, long dropped, int queued, String closeReason, String failure,
                         double targetKnown, double aimErrorKnown, double geometryKnown) {
        this(sessionId, playerName, playerUuid, state, label, cheatFamily, clientFamily, configuration,
                assistStrength, scenario, durationSeconds, frames, records, attacks, swings, attackWindows,
                dropped, queued, closeReason, failure, targetKnown, aimErrorKnown, geometryKnown, 0);
    }

    public enum State { RECORDING, CLOSING, FAILED }

    public static State stateOf(DatasetSession session) {
        if (session.failure() != null) return State.FAILED;
        return session.accepting() ? State.RECORDING : State.CLOSING;
    }

    /**
     * A counter that should not be where it is. Dropped records mean the queue overflowed, and a
     * long recording with no buildable attack window means the window configuration and the fight
     * never lined up. Both are worth an operator's attention while there is still time to redo the
     * recording; neither decides whether the session is usable.
     */
    public boolean qualityWarning() {
        if (dropped > 0 || failure != null || movementGaps > 0) return true;
        if (durationSeconds >= 60 && attackWindows == 0) return true;
        return durationSeconds >= 60 && attacks > 0 && targetKnown < 0.5;
    }

    public double durationProgress(int targetSeconds) {
        return targetSeconds <= 0 ? 0 : Math.min(1.0, durationSeconds / (double) targetSeconds);
    }

    public double windowProgress(int targetWindows) {
        return targetWindows <= 0 ? 0 : Math.min(1.0, attackWindows / (double) targetWindows);
    }

    /** "CHEAT AIM LOW" / "LEGIT" — the compact form used on the floating recording tag. */
    public String shortLabel() {
        if (label != DatasetMetadata.Label.CHEAT) return AdminLabels.datasetLabel(label);
        StringBuilder text = new StringBuilder(AdminLabels.datasetLabel(label));
        if (cheatFamily != null && !cheatFamily.isBlank()) {
            text.append(' ').append(cheatFamily.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (assistStrength != null && assistStrength != DatasetMetadata.AssistStrength.UNKNOWN) {
            text.append(' ').append(AdminLabels.assistStrength(assistStrength));
        }
        return text.toString();
    }
}
