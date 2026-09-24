package ac.grim.grimac.neural;

import ac.grim.grimac.neural.dataset.DatasetSession;
import ac.grim.grimac.neural.inference.PredictionTrail;
import ac.grim.grimac.neural.mitigation.PlayerMitigationState;
import ac.grim.grimac.neural.risk.PendingSnapshot;
import ac.grim.grimac.neural.risk.PlayerRiskProfile;
import ac.grim.grimac.neural.telemetry.CombatTelemetryCollector;

/**
 * The only additional GrimPlayer field. Nothing here is allocated until the player is actually in
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

    public PlayerRiskProfile risk;
    public PendingSnapshot pendingSnapshot;
    public long snapshotWindowStartNanos;
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
    }
}
