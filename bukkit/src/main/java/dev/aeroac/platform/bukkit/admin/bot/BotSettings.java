package dev.aeroac.platform.bukkit.admin.bot;

/**
 * Everything an operator can change about their training bot from the menu. Mutable, owned by
 * one operator, read by that bot's tick task.
 */
public final class BotSettings {
    public enum Mode { STAND, FOLLOW, STRAFE, RUN, FIGHT }

    public static final double[] SPEEDS = {0.12, 0.2, 0.28, 0.36};
    public static final double[] KNOCKBACK = {0.0, 0.5, 1.0, 1.5};
    public static final double[] DAMAGE = {0.0, 1.0, 2.0, 4.0, 6.0};
    public static final double[] HEALTH = {20, 40, 100, 1000};
    public static final double[] DISTANCE = {2.0, 3.0, 4.0, 6.0};

    public volatile Mode mode = Mode.STRAFE;
    public volatile int speed = 2;
    public volatile int knockback = 2;
    public volatile int damage = 0;
    public volatile int health = 0;
    public volatile int distance = 1;
    public volatile boolean immortal = true;
    public volatile boolean jumping = false;
    public volatile int armor = 0;
    public volatile int weapon = 0;

    public double speedValue() { return SPEEDS[speed]; }
    public double knockbackValue() { return KNOCKBACK[knockback]; }
    public double damageValue() { return DAMAGE[damage]; }
    public double healthValue() { return HEALTH[health]; }
    public double distanceValue() { return DISTANCE[distance]; }

    static int next(int value, int length) { return (value + 1) % length; }
}
