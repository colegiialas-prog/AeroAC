package ac.grim.grimac.neural.telemetry;

/** Only immutable, detached records may cross into the dataset writer. */
public interface TelemetryRecord {
    long nanoTime();
}
