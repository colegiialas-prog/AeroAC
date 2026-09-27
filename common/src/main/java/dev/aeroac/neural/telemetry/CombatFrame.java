package dev.aeroac.neural.telemetry;

public final class CombatFrame implements TelemetryRecord {
    /** 2 added MOUSE_GRID_YAW and MOUSE_GRID_PITCH; version 1 frames are still readable. */
    public static final int SCHEMA_VERSION = 2;
    private final long tick;
    private final long nanoTime;
    private final double[] values;

    public CombatFrame(long tick, long nanoTime, double[] values) {
        if (values.length != FrameField.COUNT) throw new IllegalArgumentException("Incompatible frame schema");
        this.tick = tick;
        this.nanoTime = nanoTime;
        this.values = values.clone();
    }

    public long tick() { return tick; }
    @Override public long nanoTime() { return nanoTime; }
    public double value(FrameField field) { return values[field.ordinal()]; }
}
