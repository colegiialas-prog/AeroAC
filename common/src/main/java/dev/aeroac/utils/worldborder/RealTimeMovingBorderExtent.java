package dev.aeroac.utils.worldborder;

import dev.aeroac.utils.math.AeroMath;

public class RealTimeMovingBorderExtent implements BorderExtent {

    private final double from;
    private final double to;
    private final long startTime;
    private final long endTime;

    public RealTimeMovingBorderExtent(double from, double to, long durationMs) {
        this.from = from;
        this.to = to;
        this.startTime = System.currentTimeMillis();
        this.endTime = this.startTime + durationMs;
    }

    @Override
    public double size() {
        long now = System.currentTimeMillis();
        if (now >= endTime) {
            return to;
        }
        double progress = (double) (now - startTime) / (double) (endTime - startTime);
        return progress < 1.0D ? AeroMath.lerp(progress, from, to) : to;
    }

    @Override
    public double getMinX(double centerX, double absoluteMaxSize) {
        return AeroMath.clamp(centerX - size() / 2.0, -absoluteMaxSize, absoluteMaxSize);
    }

    @Override
    public double getMaxX(double centerX, double absoluteMaxSize) {
        return AeroMath.clamp(centerX + size() / 2.0, -absoluteMaxSize, absoluteMaxSize);
    }

    @Override
    public double getMinZ(double centerZ, double absoluteMaxSize) {
        return AeroMath.clamp(centerZ - size() / 2.0, -absoluteMaxSize, absoluteMaxSize);
    }

    @Override
    public double getMaxZ(double centerZ, double absoluteMaxSize) {
        return AeroMath.clamp(centerZ + size() / 2.0, -absoluteMaxSize, absoluteMaxSize);
    }

    @Override
    public BorderExtent tick() {
        return update();
    }

    @Override
    public BorderExtent update() {
        if (System.currentTimeMillis() >= endTime) {
            return new StaticBorderExtent(to);
        }
        return this;
    }

}
