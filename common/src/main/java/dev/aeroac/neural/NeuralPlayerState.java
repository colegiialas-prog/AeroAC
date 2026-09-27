package dev.aeroac.neural;

import dev.aeroac.neural.dataset.DatasetSession;
import dev.aeroac.neural.inference.PredictionTrail;
import dev.aeroac.neural.mitigation.PlayerMitigationState;
import dev.aeroac.neural.risk.PendingSnapshot;
import dev.aeroac.neural.risk.PlayerRiskProfile;
import dev.aeroac.neural.telemetry.CombatTelemetryCollector;

/**
 * The only additional AeroPlayer field. Nothing here is allocated until the player is actually in
 * combat with telemetry enabled, so an idle or non-combat connection costs one empty object.
 *
 * <p>Everything except the explicitly volatile fields is confined to the player event loop.
 */
public final class NeuralPlayerState {
    // Session/open state is touched on the player event loop. Disconnect only closes the volatile session.
    public volatile DatasetSession session;
    public boolean opening;
    public volatile boolean cancelOpening;
    public volatile boolean disconnected;
    public CombatTelemetryCollector collector;

    public PredictionTrail trail;
    public long lastFlashNanos;
    public long lastProNanos;
    public long inferenceSkipped;
    /** End of the last window that produced evidence, per ModelKind ordinal; 0 = none yet. */
    public final long[] lastScoredWindowEndNanos = new long[2];
    public final boolean[] scoredWindowSeen = new boolean[2];

    public PlayerRiskProfile risk;
    public PendingSnapshot pendingSnapshot;
    public long snapshotWindowStartNanos;
    public boolean snapshotWindowOpen;
    public int snapshotsInWindow;

    public PlayerMitigationState mitigation;

    /** Read on the packet path to skip monitor formatting entirely when nobody is watching. */
    public volatile boolean monitored;
    public long lastMonitorNanos;

    public void stop(String reason) {
        cancelOpening = true;
        DatasetSession current = session;
        if (current != null) current.close(reason);
        CombatTelemetryCollector active = collector;
        if (active != null) active.detach();
    }

    /** Drops per-connection derived state without touching the dataset session. */
    public void resetRuntime() {
        collector = null;
        trail = null;
        risk = null;
        pendingSnapshot = null;
        mitigation = null;
        lastFlashNanos = lastProNanos = 0;
        java.util.Arrays.fill(lastScoredWindowEndNanos, 0);
        java.util.Arrays.fill(scoredWindowSeen, false);
    }
}
