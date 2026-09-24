package dev.aeroac.checks.impl.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Replays the item-use patterns of NoSlow clients and of honest players against the NoSlow decision. */
class UnslowedTickBufferTest {

    private static int flagsFor(UnslowedTickBuffer buffer, boolean[] pattern, int ticks) {
        int flags = 0;
        for (int tick = 0; tick < ticks; tick++) {
            if (buffer.usingItem(pattern[tick % pattern.length], false)) flags++;
        }
        return flags;
    }

    @Test void grimNewEveryOtherTickIsCaught() {
        // "Grim New": the slowdown is skipped on even use ticks and kept on odd ones.
        UnslowedTickBuffer buffer = new UnslowedTickBuffer(2.0, 0.2);
        int firstFlag = -1;
        for (int tick = 0; tick < 32; tick++) {
            if (buffer.usingItem(tick % 2 == 0, false) && firstFlag < 0) firstFlag = tick;
        }
        assertTrue(firstFlag >= 0 && firstFlag <= 4, "first flag at tick " + firstFlag);
        assertTrue(flagsFor(new UnslowedTickBuffer(2.0, 0.2), new boolean[]{true, false}, 32) >= 12);
    }

    @Test void neverSlowedFlagsOnTheSecondTick() {
        UnslowedTickBuffer buffer = new UnslowedTickBuffer(2.0, 0.2);
        assertFalse(buffer.usingItem(true, false));
        assertTrue(buffer.usingItem(true, false));
    }

    @Test void everyPatternDenserThanOneInSixIsCaught() {
        for (int period = 2; period <= 5; period++) {
            boolean[] pattern = new boolean[period];
            pattern[0] = true;
            assertTrue(flagsFor(new UnslowedTickBuffer(2.0, 0.2), pattern, 200) > 0, "period " + period);
        }
        boolean[] sparse = new boolean[6];
        sparse[0] = true;
        assertEquals(0, flagsFor(new UnslowedTickBuffer(2.0, 0.2), sparse, 600));
    }

    @Test void honestUseWithADesyncAtEachEndNeverFlags() {
        UnslowedTickBuffer buffer = new UnslowedTickBuffer(2.0, 0.2);
        for (int use = 0; use < 50; use++) {
            // A 32-tick eat, first and last tick desynced, then a few ticks without an item.
            for (int tick = 0; tick < 32; tick++) {
                assertFalse(buffer.usingItem(tick == 0 || tick == 31, false), "use " + use + " tick " + tick);
            }
            for (int tick = 0; tick < 3; tick++) buffer.notUsingItem();
        }
    }

    @Test void rapidShortUsesWithOneDesyncEachNeverFlag() {
        // Shield or bow spam: an 8-tick use with one desynced tick, back to back.
        UnslowedTickBuffer buffer = new UnslowedTickBuffer(2.0, 0.2);
        for (int use = 0; use < 100; use++) {
            for (int tick = 0; tick < 8; tick++) assertFalse(buffer.usingItem(tick == 0, false));
            buffer.notUsingItem();
        }
    }

    @Test void excusedTicksDoNotAccumulateOrChain() {
        UnslowedTickBuffer buffer = new UnslowedTickBuffer(2.0, 0.2);
        for (int tick = 0; tick < 20; tick++) {
            assertFalse(buffer.usingItem(true, true));
            assertFalse(buffer.usingItem(false, false));
        }
        assertEquals(0.0, buffer.value(), 1e-9);
    }

    @Test void aGapWithoutAnItemBreaksTheConsecutiveRun() {
        UnslowedTickBuffer buffer = new UnslowedTickBuffer(5.0, 1.0);
        assertFalse(buffer.usingItem(true, false));
        buffer.notUsingItem();
        assertFalse(buffer.usingItem(true, false));
    }

    @Test void rejectsNonsenseSettings() {
        assertThrows(IllegalArgumentException.class, () -> new UnslowedTickBuffer(0.5, 0.2));
        assertThrows(IllegalArgumentException.class, () -> new UnslowedTickBuffer(2.0, -1));
    }
}
