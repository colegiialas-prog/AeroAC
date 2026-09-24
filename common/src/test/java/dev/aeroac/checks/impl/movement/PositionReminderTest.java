package dev.aeroac.checks.impl.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Replays the packet stream of a vanilla client and of an air-stuck client against the AirStuck counter. */
class PositionReminderTest {

    @Test void vanillaStandingStillNeverFlags() {
        // LocalPlayer.sendPosition: positionReminder reaches 20 and forces a position, then the tick ends.
        PositionReminder reminder = new PositionReminder(25);
        for (int tick = 0; tick < 2000; tick++) {
            if (tick % PositionReminder.VANILLA_MAX_TICKS == 0) reminder.position();
            assertEquals(0, reminder.tickEnd(false), "tick " + tick);
        }
    }

    @Test void vanillaBoundIsExactlyTwentyTickEnds() {
        PositionReminder reminder = new PositionReminder(PositionReminder.VANILLA_MAX_TICKS);
        reminder.position();
        for (int tick = 0; tick < PositionReminder.VANILLA_MAX_TICKS; tick++) assertEquals(0, reminder.tickEnd(false));
        assertEquals(PositionReminder.VANILLA_MAX_TICKS, reminder.ticks());
        assertEquals(PositionReminder.VANILLA_MAX_TICKS + 1, reminder.tickEnd(false));
    }

    @Test void frozenClientFlagsAndKeepsFlagging() {
        // AirStuck: every movement packet cancelled, CLIENT_TICK_END still sent every tick.
        PositionReminder reminder = new PositionReminder(25);
        reminder.position();
        int flags = 0;
        int firstFlag = -1;
        for (int tick = 1; tick <= 200; tick++) {
            if (reminder.tickEnd(false) > 0) {
                flags++;
                if (firstFlag < 0) firstFlag = tick;
            }
        }
        assertEquals(26, firstFlag);
        assertEquals(200 / 26, flags);
    }

    @Test void nothingCountsBeforeTheFirstPositionOrWhileExempt() {
        PositionReminder reminder = new PositionReminder(25);
        for (int tick = 0; tick < 500; tick++) assertEquals(0, reminder.tickEnd(false));
        reminder.position();
        for (int tick = 0; tick < 500; tick++) assertEquals(0, reminder.tickEnd(true));
        reminder.disarm();
        for (int tick = 0; tick < 500; tick++) assertEquals(0, reminder.tickEnd(false));
        assertFalse(reminder.armed());
    }

    @Test void theLimitCannotBeConfiguredBelowVanilla() {
        assertEquals(PositionReminder.VANILLA_MAX_TICKS, new PositionReminder(3).maxTicks());
        assertEquals(40, new PositionReminder(40).maxTicks());
    }
}
