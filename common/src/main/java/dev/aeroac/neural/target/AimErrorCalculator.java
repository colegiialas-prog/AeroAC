package dev.aeroac.neural.target;

/** Target point selection belongs to Grim's compensated geometry, not to this angle conversion. */
public final class AimErrorCalculator {
    private AimErrorCalculator() { }

    public static double normalizeYaw(double yaw) {
        double wrapped = yaw % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    public static AimError calculate(double yaw, double pitch, double dx, double dy, double dz) {
        if (!Double.isFinite(yaw + pitch + dx + dy + dz) || dx * dx + dy * dy + dz * dz < 1.0E-16) {
            return new AimError(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double targetYaw = normalizeYaw(Math.toDegrees(Math.atan2(-dx, dz)));
        double targetPitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        double errorYaw = normalizeYaw(yaw - targetYaw);
        double errorPitch = pitch - targetPitch;
        return new AimError(targetYaw, targetPitch, errorYaw, errorPitch, Math.hypot(errorYaw, errorPitch));
    }

    public record AimError(double targetYaw, double targetPitch, double yaw, double pitch, double total) { }
}
