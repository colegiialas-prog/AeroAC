package ac.grim.grimac.neural.risk;

/**
 * One observation offered to the engine. Strength is a risk delta in engine units, already weighted
 * by the caller; a negative strength is relief. Metadata is operator-facing text only and never a
 * model input.
 */
public record Evidence(EvidenceType type, double strength, long nanoTime, String source, String metadata) {

    public Evidence {
        if (type == null) throw new IllegalArgumentException("Missing evidence type");
        if (!Double.isFinite(strength)) throw new IllegalArgumentException("Non-finite evidence strength");
        if (type == EvidenceType.AI_RELIEF && strength > 0) {
            throw new IllegalArgumentException("Relief cannot raise risk");
        }
        if (type != EvidenceType.AI_RELIEF && strength < 0) {
            throw new IllegalArgumentException("Only relief may carry negative strength");
        }
        if (source == null) throw new IllegalArgumentException("Missing evidence source");
    }

    public static Evidence of(EvidenceType type, double strength, long nanoTime, String source) {
        return new Evidence(type, strength, nanoTime, source, null);
    }

    public String describe() {
        return type + "=" + String.format("%+.2f", strength) + " (" + source
                + (metadata == null || metadata.isBlank() ? "" : ", " + metadata) + ")";
    }
}
