package ac.grim.grimac.neural.window;

import ac.grim.grimac.neural.telemetry.CombatFrame;
import ac.grim.grimac.neural.telemetry.FrameField;

/** Extraction utility only: Phase 1 persists raw records, not duplicated overlapping windows. */
public final class AttackWindowBuilder {
    private AttackWindowBuilder() { }

    public static CombatFrame[] extract(TemporalRingBuffer ring, long attackTick, int before, int after) {
        if (before < 0 || after < 0 || (long) before + after + 1 > ring.capacity()) {
            throw new IllegalArgumentException("Window outside capacity");
        }
        long firstTick = attackTick - before;
        for (int start = 0; start + before + after < ring.size(); start++) {
            if (ring.get(start).tick() != firstTick) continue;
            int length = before + after + 1;
            for (int i = 0; i < length; i++) {
                CombatFrame frame = ring.get(start + i);
                if (frame.tick() != firstTick + i || (i > 0 && frame.value(FrameField.SEGMENT_START) == 1)) return null;
            }
            if (ring.get(start + before).value(FrameField.ATTACK) != 1) return null;
            CombatFrame[] window = new CombatFrame[length];
            for (int i = 0; i < length; i++) window[i] = ring.get(start + i);
            return window;
        }
        return null;
    }
}
