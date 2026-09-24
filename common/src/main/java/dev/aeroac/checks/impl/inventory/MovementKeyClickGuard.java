package dev.aeroac.checks.impl.inventory;

/**
 * Decides when an inventory click with movement keys held is impossible for a vanilla 1.21.2+ client.
 * Pure logic, so the packet orders it relies on can be replayed in a unit test.
 *
 * <p>Opening any screen releases every key ({@code Minecraft#setScreen} calls {@code KeyMapping.releaseAll}),
 * and the next client tick reports the released keys with {@code PLAYER_INPUT} before its tick end. A click
 * needs the screen, so for the player's own inventory - opened by the client inside a tick - the released
 * keys always arrive before the first click.
 *
 * <p>A screen the server opens is shown while the client handles packets, and a fast frame with no tick in
 * it could send a click before that tick. So after the server opens a screen, clicks are only judged once
 * the client has confirmed the transaction after the open packet and then ended a tick.
 */
public final class MovementKeyClickGuard {
    private int shownAtTransaction = Integer.MIN_VALUE;
    private boolean tickedSinceShown = true;

    /**
     * @param confirmTransaction a transaction certainly sent after the open packet
     */
    public void serverOpenedScreen(int confirmTransaction) {
        shownAtTransaction = confirmTransaction;
        tickedSinceShown = false;
    }

    public void tickEnd(int lastTransactionReceived) {
        if (lastTransactionReceived >= shownAtTransaction) tickedSinceShown = true;
    }

    public boolean impossibleClick(boolean movementKeysHeld, int lastTransactionReceived) {
        return movementKeysHeld && tickedSinceShown && lastTransactionReceived >= shownAtTransaction;
    }
}
