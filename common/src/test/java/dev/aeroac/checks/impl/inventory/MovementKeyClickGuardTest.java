package dev.aeroac.checks.impl.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovementKeyClickGuardTest {

    @Test void ownInventoryClickIsJudgedAtOnce() {
        // The client opens its own inventory inside a tick, which reports the released keys first
        MovementKeyClickGuard guard = new MovementKeyClickGuard();
        assertFalse(guard.impossibleClick(false, 5), "vanilla: keys released before the click");
        assertTrue(guard.impossibleClick(true, 5), "keys still held: swapped without a screen");
    }

    @Test void serverScreenWaitsForConfirmationAndATick() {
        MovementKeyClickGuard guard = new MovementKeyClickGuard();
        guard.serverOpenedScreen(12);

        // The open packet may not have been handled yet
        assertFalse(guard.impossibleClick(true, 11));
        // Handled, but a fast frame can click before the tick that reports the released keys
        assertFalse(guard.impossibleClick(true, 12));
        // A tick that ended before the confirmation proves nothing
        guard.tickEnd(11);
        assertFalse(guard.impossibleClick(true, 12));

        guard.tickEnd(12);
        assertTrue(guard.impossibleClick(true, 12));
        assertFalse(guard.impossibleClick(false, 12));
    }

    @Test void aNewScreenRestartsTheWait() {
        MovementKeyClickGuard guard = new MovementKeyClickGuard();
        guard.serverOpenedScreen(3);
        guard.tickEnd(3);
        assertTrue(guard.impossibleClick(true, 3));

        guard.serverOpenedScreen(8);
        assertFalse(guard.impossibleClick(true, 8));
        guard.tickEnd(9);
        assertTrue(guard.impossibleClick(true, 9));
    }
}
