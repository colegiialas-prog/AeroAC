package dev.aeroac.neural.telemetry;

public final class CombatFrame implements TelemetryRecord {
    public static final int SCHEMA_VERSION = 1;
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
