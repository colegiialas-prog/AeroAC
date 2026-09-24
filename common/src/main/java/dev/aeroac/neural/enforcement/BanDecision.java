package dev.aeroac.neural.enforcement;

import dev.aeroac.neural.risk.RiskState;

import java.util.UUID;

/**
 * One verdict the enforcement layer reached, frozen at the moment it reached it.
 *
 * <p>Frozen on purpose. Between a decision and a human pressing confirm, the player keeps playing:
 * risk decays, the model produces new output, the state may fall back. A confirmation has to act on
 * the evidence that was actually shown to the person who confirmed it, not on whatever the numbers
 * happen to be a minute later — otherwise the staff approve one thing and the server does another.
 */
public record BanDecision(String id, UUID uuid, String name, RiskState state, double risk,
                          double overall, String dominant, int evidence, int predictions,
                          long decidedAtMillis) {

    /** Short, unambiguous and typeable: it goes into a command an operator may have to retype. */
    public static String nextId(UUID player, long millis) {
        String suffix = Long.toString(Math.abs(player.getLeastSignificantBits() ^ millis), 36);
        return suffix.length() <= 6 ? suffix : suffix.substring(suffix.length() - 6);
    }

    public boolean expired(long nowMillis, int timeoutSeconds) {
        return nowMillis - decidedAtMillis > timeoutSeconds * 1000L;
    }
}
