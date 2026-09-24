package dev.aeroac.neural.telemetry;

/** Reuses Grim's conservative multi-ray result; never means server-confirmed damage. */
public record ReachObservation(long nanoTime, int entityId, double distance,
                               int hitboxIntersection, int lineOfSight,
                               double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ) implements TelemetryRecord { }
