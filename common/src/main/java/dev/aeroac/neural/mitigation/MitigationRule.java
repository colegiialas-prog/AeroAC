package dev.aeroac.neural.mitigation;

/**
 * Only server-authoritative actions belong here. Cancelling reuses the packet cancellation the
 * anticheat already performs for impossible hits, so a mitigated player simply misses; the damage
 * multiplier is applied in the platform's own damage event, where the server computes the hit.
 */
public enum MitigationRule {
    /** Records that the threshold was reached and changes nothing about gameplay. */
    OBSERVE,
    /** Drops every attack packet of the player for the duration of the action. */
    CANCEL_ATTACKS,
    /** Drops a share of attacks and/or scales the damage dealt: weaker, and far harder to notice. */
    DAMPEN
}
