package dev.aeroac.neural.admin.training;

import dev.aeroac.locale.AeroMessages;

/**
 * One recorded session, as its metadata file describes it.
 *
 * <p>Read from {@code datasets/metadata/session-*.json}, which the recorder writes on open and
 * rewrites on close. The raw telemetry beside it is never opened by the interface: it is large, it
 * is personal data about the players who produced it, and nothing on an inventory screen needs it.
 *
 * <p>{@link #auditVerdict} and {@link #reviewer} are null unless an offline result was actually
 * imported. A session the audit has never seen is shown as unaudited, never as GOOD — the runtime
 * has no way to earn that word and must not borrow it.
 */
public record SessionRecord(String sessionId, String playerId, long startTimestamp, String label,
                            String labelSource, String cheatFamily, String clientFamily, String configuration,
                            String scenario, String assistStrength, int minecraftProtocol, long durationMs,
                            long frames, long records, long droppedRecords, boolean complete,
                            String closeReason, String failure,
                            String auditVerdict, String auditReason, String reviewer, String reviewedAt,
                            String reviewedLabel) {

    public boolean legit() { return "LEGIT".equals(label); }
    public boolean cheat() { return "CHEAT".equals(label); }
    public boolean unlabeled() { return "UNLABELED".equals(label); }

    public boolean audited() { return auditVerdict != null && !auditVerdict.isBlank(); }
    public boolean humanReviewed() { return reviewer != null && !reviewer.isBlank(); }

    /**
     * Sessions worth an operator's attention before anything is trained on them.
     *
     * <p>Imported REVIEW verdicts, plus the three runtime facts that are not judgements at all:
     * the session never closed cleanly, the recorder failed, or records were dropped. None of
     * these says the data is bad; all of them say a person should look.
     */
    public boolean needsAttention() {
        if ("REVIEW".equals(auditVerdict) || "UNUSABLE".equals(auditVerdict)) return true;
        return !complete || failure != null || droppedRecords > 0;
    }

    public String attentionReason() {
        // The audit's own words come through untouched: it wrote them, and paraphrasing a verdict
        // is how a verdict stops meaning what it said.
        if (auditReason != null && !auditReason.isBlank()) return auditReason;
        if (failure != null) return AeroMessages.tr("gui.session.recorder_failure") + failure;
        if (!complete) return AeroMessages.tr("gui.session.never_closed")
                + (closeReason == null || closeReason.isBlank() ? "" : " (" + closeReason + ")");
        if (droppedRecords > 0) return AeroMessages.tr("gui.session.dropped_records", droppedRecords);
        return "";
    }

    public long durationSeconds() { return durationMs / 1000L; }

    public String shortId() {
        return sessionId == null || sessionId.length() < 8 ? String.valueOf(sessionId) : sessionId.substring(0, 8);
    }
}
