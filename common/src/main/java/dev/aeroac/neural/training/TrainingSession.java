package dev.aeroac.neural.training;

import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;

import java.util.ArrayList;
import java.util.List;

/**
 * One labelled recording as the trainer sees it: metadata plus frames in order. The Java twin of
 * {@code Session} in ml/aeroml/dataset/records.py.
 */
public record TrainingSession(String sessionId, String playerId, String clientFamily, String configuration,
                              String label, String labelSource, String cheatFamily, String notes,
                              String pluginVersion, long durationMs, CombatFrame[] frames, boolean staffReview) {

    public boolean cheat() { return "CHEAT".equals(label); }

    public boolean synthetic() {
        return (notes != null && notes.toLowerCase(java.util.Locale.ROOT).contains("synthetic"))
                || (pluginVersion != null && pluginVersion.toLowerCase(java.util.Locale.ROOT).contains("synthetic"));
    }

    public String client() { return clientFamily == null || clientFamily.isBlank() ? "unknown" : clientFamily; }

    public double value(int index, FrameField field) { return frames[index].value(field); }

    /**
     * Half-open [start, end) runs with no segment boundary and no tick hole inside, exactly as
     * Session.segments: a SEGMENT_START flag, a tick step other than one, or a timestamp step that
     * is not positive or exceeds 150 ms all end a run.
     */
    public List<int[]> segments() {
        List<int[]> result = new ArrayList<>();
        if (frames.length == 0) return result;
        int start = 0;
        for (int i = 1; i < frames.length; i++) {
            long elapsed = frames[i].nanoTime() - frames[i - 1].nanoTime();
            boolean cut = frames[i].value(FrameField.SEGMENT_START) == 1
                    || frames[i].tick() - frames[i - 1].tick() != 1
                    || elapsed <= 0 || elapsed > 150_000_000L;
            if (cut) {
                result.add(new int[]{start, i});
                start = i;
            }
        }
        result.add(new int[]{start, frames.length});
        return result;
    }

    /**
     * Observed combat seconds: at most 50 ms after each sample with a target or an attack, bounded by
     * the next sample and the session end (ml/aeroml/dataset/exposure.py). Frame times are offsets
     * from the session start, as {@link TrainingDataset} loads them.
     */
    public double combatSeconds() {
        if (frames.length == 0) return 0;
        double end = Math.max(0, durationMs) * 1e6;
        double total = 0;
        for (int i = 0; i < frames.length; i++) {
            boolean active = frames[i].value(FrameField.TARGET_PRESENT) == 1 || frames[i].value(FrameField.ATTACK) == 1;
            if (!active) continue;
            double here = frames[i].nanoTime();
            double next = i + 1 < frames.length ? frames[i + 1].nanoTime() : end;
            total += Math.max(0, Math.min(50_000_000, next - here)) / 1e9;
        }
        return total;
    }
}
