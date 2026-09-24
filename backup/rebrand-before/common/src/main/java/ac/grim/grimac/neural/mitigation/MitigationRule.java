package ac.grim.grimac.neural.mitigation;

/**
 * Only server-authoritative actions belong here. CANCEL_ATTACKS reuses the packet cancellation the
 * anticheat already performs for impossible hits, so a mitigated player simply misses.
 *
 * <p>A damage multiplier is deliberately absent: this module has no platform damage hook, and a
 * client-side or half-applied effect would desynchronise combat rather than dampen it.
 */
public enum MitigationRule {
    /** Records that the threshold was reached and changes nothing about gameplay. */
    OBSERVE,
    /** Drops the player's attack packets for the duration of the action. */
    CANCEL_ATTACKS
}
