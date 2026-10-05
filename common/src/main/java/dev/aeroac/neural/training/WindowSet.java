package dev.aeroac.neural.training;

import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows referenced, not copied: (session, start). Built with the rules of
 * ml/aeroml/dataset/windows.py: an attack window needs its full history on both sides inside one
 * segment; a continuous window never crosses a segment and, for training, contains a target.
 */
public final class WindowSet {
    public final List<TrainingSession> sessions;
    final int[] session;
    final int[] start;
    public final int length;
    public final String kind;

    private WindowSet(List<TrainingSession> sessions, int[] session, int[] start, int length, String kind) {
        this.sessions = sessions;
        this.session = session;
        this.start = start;
        this.length = length;
        this.kind = kind;
    }

    public int size() { return session.length; }

    public static WindowSet attack(List<TrainingSession> sessions, int before, int after) {
        List<int[]> refs = new ArrayList<>();
        for (int s = 0; s < sessions.size(); s++) {
            TrainingSession recording = sessions.get(s);
            for (int[] segment : recording.segments()) {
                for (int anchor = segment[0] + before; anchor + after < segment[1]; anchor++) {
                    if (recording.frames()[anchor].value(FrameField.ATTACK) == 1) refs.add(new int[]{s, anchor - before});
                }
            }
        }
        return of(sessions, refs, before + after + 1, "attack");
    }

    public static WindowSet continuous(List<TrainingSession> sessions, int length, int stride) {
        List<int[]> refs = new ArrayList<>();
        for (int s = 0; s < sessions.size(); s++) {
            TrainingSession recording = sessions.get(s);
            for (int[] segment : recording.segments()) {
                for (int begin = segment[0]; begin <= segment[1] - length; begin += stride) {
                    boolean target = false;
                    for (int t = begin; t < begin + length && !target; t++) {
                        target = recording.frames()[t].value(FrameField.TARGET_PRESENT) == 1;
                    }
                    if (target) refs.add(new int[]{s, begin});
                }
            }
        }
        return of(sessions, refs, length, "continuous");
    }

    private static WindowSet of(List<TrainingSession> sessions, List<int[]> refs, int length, String kind) {
        int[] session = new int[refs.size()], start = new int[refs.size()];
        for (int i = 0; i < refs.size(); i++) { session[i] = refs.get(i)[0]; start[i] = refs.get(i)[1]; }
        return new WindowSet(List.copyOf(sessions), session, start, length, kind);
    }

    public TrainingSession sessionOf(int window) { return sessions.get(session[window]); }

    public int label(int window) { return sessionOf(window).cheat() ? 1 : 0; }

    /** Encoded, not yet normalised: row-major [t * FEATURE_COUNT + channel]. */
    public float[] encode(int window) {
        CombatFrame[] frames = sessionOf(window).frames();
        CombatFrame[] slice = new CombatFrame[length];
        System.arraycopy(frames, start[window], slice, 0, length);
        return FeatureEncoder.encode(slice);
    }

    public String attribute(String name, int window) {
        TrainingSession recording = sessionOf(window);
        return switch (name) {
            case "session" -> recording.sessionId();
            case "player" -> recording.playerId();
            case "client" -> recording.client();
            case "configuration" -> recording.configuration() == null ? "default" : recording.configuration();
            default -> throw new IllegalArgumentException(name);
        };
    }

    public String describe() {
        int cheat = 0;
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int i = 0; i < size(); i++) { cheat += label(i); used.add(session[i]); }
        return kind + " windows=" + size() + " length=" + length + " cheat=" + cheat + " legit=" + (size() - cheat)
                + " sessions=" + used.size();
    }
}
