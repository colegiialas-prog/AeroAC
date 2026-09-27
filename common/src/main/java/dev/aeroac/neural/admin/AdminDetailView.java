package dev.aeroac.neural.admin;

import dev.aeroac.neural.risk.EvidenceType;
import dev.aeroac.neural.risk.RiskState;

import java.util.List;

/**
 * The deep history behind one player, built only when an administrator opens a history screen.
 *
 * <p>Everything comes out of bounded structures that already exist: the prediction trail, the risk
 * profile's evidence ring and the mitigation history. Nothing is stored for the interface and
 * nothing grows: what the ring buffers have dropped is gone, and the screen says so.
 */
public record AdminDetailView(AdminPlayerView summary, List<Prediction> predictions, List<Evidence> evidence,
                              List<Mitigation> mitigations, long[] evidenceCounts, double[] timeline,
                              long trailAccepted, long trailStaleDropped, double[] riskTimeline) {

    /** Without a risk timeline, as views built before it existed. */
    public AdminDetailView(AdminPlayerView summary, List<Prediction> predictions, List<Evidence> evidence,
                           List<Mitigation> mitigations, long[] evidenceCounts, double[] timeline,
                           long trailAccepted, long trailStaleDropped) {
        this(summary, predictions, evidence, mitigations, evidenceCounts, timeline, trailAccepted, trailStaleDropped,
                new double[0]);
    }

    public AdminDetailView {
        riskTimeline = riskTimeline == null ? new double[0] : riskTimeline.clone();
        predictions = List.copyOf(predictions);
        evidence = List.copyOf(evidence);
        mitigations = List.copyOf(mitigations);
        evidenceCounts = evidenceCounts == null ? new long[0] : evidenceCounts.clone();
        timeline = timeline == null ? new double[0] : timeline.clone();
    }

    @Override public long[] evidenceCounts() { return evidenceCounts.clone(); }
    @Override public double[] timeline() { return timeline.clone(); }
    /** Accumulated risk after each of the most recent evidence items, oldest first. */
    @Override public double[] riskTimeline() { return riskTimeline.clone(); }

    public record Prediction(String model, String modelVersion, boolean calibrated, String window,
                             double overall, String[] headNames, double[] headValues,
                             long latencyMs, long ageMs) {
        public Prediction {
            headNames = headNames.clone();
            headValues = headValues.clone();
        }
        @Override public String[] headNames() { return headNames.clone(); }
        @Override public double[] headValues() { return headValues.clone(); }
    }

    public record Evidence(EvidenceType type, double strength, String source, String metadata, long ageMs) { }

    public record Mitigation(String rule, double riskAtTrigger, RiskState stateAtTrigger, String reason,
                             long durationMs, long remainingMs, long ageMs) { }

    public long countOf(EvidenceType type) {
        return evidenceCounts == null || type.ordinal() >= evidenceCounts.length ? 0 : evidenceCounts[type.ordinal()];
    }

    /** Deterministic-check evidence only, for the GRIM FLAGS screen. */
    public long grimFlags() {
        long total = 0;
        for (EvidenceType type : EvidenceType.values()) if (!type.fromModel()) total += countOf(type);
        return total;
    }

    public static AdminDetailView empty(AdminPlayerView summary) {
        return new AdminDetailView(summary, List.of(), List.of(), List.of(),
                new long[EvidenceType.values().length], new double[0], 0, 0);
    }
}
