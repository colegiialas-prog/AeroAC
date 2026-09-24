package dev.aeroac.neural.admin;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.inference.PredictionResult;

/**
 * The strongest specific head a prediction published, for the one-line indicator.
 *
 * <p>Display only. Nothing downstream may branch on this: the risk engine weighs every head it
 * accepts, and naming the loudest one is a convenience for an operator reading a name tag, not a
 * classification of what the player was doing.
 */
public record DominantSignal(String label, double value) {
    /** Shown when a prediction exists but publishes no specific head above the others. */
    public static final String GENERIC = "AI";

    private static final String[] HEADS = {"aimAssist", "killAura", "triggerBot"};
    private static final String[] LABELS = {AeroMessages.tr("admin.aim"), "AURA", AeroMessages.tr("admin.trigger")};

    /** Returns null when there is no prediction at all, so callers render NO DATA rather than 0%. */
    public static DominantSignal of(PredictionResult prediction) {
        if (prediction == null) return null;
        String label = null;
        double best = Double.NaN;
        for (int i = 0; i < HEADS.length; i++) {
            double value = prediction.head(HEADS[i]);
            if (Double.isNaN(value)) continue;
            if (Double.isNaN(best) || value > best) {
                best = value;
                label = LABELS[i];
            }
        }
        if (label == null) {
            double overall = prediction.overall();
            return Double.isNaN(overall) ? null : new DominantSignal(GENERIC, overall);
        }
        return new DominantSignal(label, best);
    }

    public boolean generic() { return GENERIC.equals(label); }
}
