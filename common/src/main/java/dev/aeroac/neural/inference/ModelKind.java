package dev.aeroac.neural.inference;

import java.util.Locale;

/** Flash runs often and cheaply; Pro is escalation only. Both share one wire protocol. */
public enum ModelKind {
    FLASH, PRO;

    public String wireName() { return name().toLowerCase(Locale.ROOT); }
}
