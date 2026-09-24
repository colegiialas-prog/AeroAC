package dev.aeroac.neural.inference;

import java.util.Locale;

/** How a request selects its samples. An unknown name falls back to ATTACK rather than failing a reload. */
public enum ModelWindow {
    /** attackBefore samples, the attack sample, attackAfter samples; complete and inside one segment. */
    ATTACK,
    /** The most recent consecutive samples inside one segment, independent of attacks. */
    CONTINUOUS;

    public static ModelWindow parse(String name) {
        if (name == null) return ATTACK;
        return CONTINUOUS.name().equalsIgnoreCase(name.trim().toLowerCase(Locale.ROOT)) ? CONTINUOUS : ATTACK;
    }

    public String wireName() { return name().toLowerCase(Locale.ROOT); }
}
