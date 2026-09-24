package dev.aeroac.neural.telemetry;

/** Arrival timing preserves multiple attacks/swings between movement samples. IDs are operational only. */
public record CombatEvent(long nanoTime, long precedingTick, String type, int entityId,
                          boolean cancelled, double yaw, double pitch) implements TelemetryRecord { }
