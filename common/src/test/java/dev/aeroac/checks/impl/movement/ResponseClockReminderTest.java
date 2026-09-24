package dev.aeroac.checks.impl.movement;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays what the server receives from a pre-1.21.2 client: transaction answers and positions, in order,
 * after latency and jitter. The client runs frames like {@code Minecraft.runTick}: handle every packet that
 * has arrived (answering transactions), then run the owed ticks, at most 10, each of which reports the
 * position on every 20th tick ({@code LocalPlayer.positionReminder}).
 */
class ResponseClockReminderTest {
    private static final long MS = 1_000_000L;

    private record Arrival(long time, boolean position) {}

    /**
     * @param frameMs      client frame lengths, cycled
     * @param airStuck     the client never reports its position
     * @param tickRate     server {@code /tick rate}, which the client ticks at and the server sends transactions at
     * @param freezeAtMs   a single client freeze starts here (negative for none)
     * @param freezeMs     how long it lasts
     */
    private static List<Arrival> simulate(long[] frameMs, boolean airStuck, float tickRate, long durationMs,
                                          long latencyMs, long jitterMs, long freezeAtMs, long freezeMs, long seed) {
        Random random = new Random(seed);
        double msPerTick = 1000.0 / tickRate;
        List<Arrival> arrivals = new ArrayList<>();
        long lastArrival = 0;

        // Server sends a transaction every server tick; it reaches the client after the latency
        long nextTransactionAtClient = latencyMs;
        double tickResidual = 0;
        int positionReminder = 0;
        long now = 0;
        int frame = 0;

        while (now < durationMs) {
            long length = frameMs[frame++ % frameMs.length];
            if (freezeAtMs >= 0 && now <= freezeAtMs && freezeAtMs < now + length) length += freezeMs;
            now += length;

            // Answer every transaction that has arrived
            while (nextTransactionAtClient <= now) {
                lastArrival = Math.max(lastArrival, now + latencyMs + (jitterMs > 0 ? random.nextInt((int) jitterMs + 1) : 0));
                arrivals.add(new Arrival(lastArrival, false));
                nextTransactionAtClient += (long) msPerTick;
            }

            tickResidual += length / msPerTick;
            int owed = (int) tickResidual;
            tickResidual -= owed;
            for (int i = 0; i < Math.min(10, owed); i++) {
                if (++positionReminder >= 20) {
                    positionReminder = 0;
                    if (!airStuck) {
                        lastArrival = Math.max(lastArrival, now + latencyMs + (jitterMs > 0 ? random.nextInt((int) jitterMs + 1) : 0));
                        arrivals.add(new Arrival(lastArrival, true));
                    }
                }
            }
        }
        return arrivals;
    }

    private static int flags(ResponseClockReminder reminder, List<Arrival> arrivals) {
        reminder.position(); // the client reported a position when it spawned
        int flags = 0;
        for (Arrival arrival : arrivals) {
            if (arrival.position()) {
                reminder.position();
            } else if (reminder.response(arrival.time() * MS, false) > 0) {
                flags++;
            }
        }
        return flags;
    }

    @Test void vanillaNeverFlagsAtAnyFrameRate() {
        for (long frame : new long[]{4, 16, 33, 50, 100, 150, 200, 250, 300, 500, 700, 1000, 2000, 3000, 5000}) {
            for (long jitter : new long[]{0, 30, 150, 400}) {
                List<Arrival> arrivals = simulate(new long[]{frame}, false, 20, 120_000, 40, jitter, -1, 0, frame * 31 + jitter);
                assertEquals(0, flags(new ResponseClockReminder(3), arrivals), "frame " + frame + "ms jitter " + jitter + "ms");
            }
        }
    }

    @Test void vanillaNeverFlagsWithStutteringFrames() {
        Random random = new Random(3);
        for (int run = 0; run < 200; run++) {
            long[] frames = new long[64];
            for (int i = 0; i < frames.length; i++) frames[i] = 5 + random.nextInt(random.nextBoolean() ? 60 : 900);
            List<Arrival> arrivals = simulate(frames, false, 20, 60_000, random.nextInt(300), random.nextInt(300), 20_000, random.nextInt(8000), run);
            assertEquals(0, flags(new ResponseClockReminder(3), arrivals), "run " + run);
        }
    }

    @Test void vanillaNeverFlagsAtASlowServerTickRate() {
        for (float tickRate : new float[]{1, 2, 4, 5, 8, 10, 15}) {
            ResponseClockReminder reminder = new ResponseClockReminder(3);
            reminder.setTickRate(tickRate);
            List<Arrival> arrivals = simulate(new long[]{16}, false, tickRate, 240_000, 40, 50, -1, 0, (long) tickRate);
            assertEquals(0, flags(reminder, arrivals), "tick rate " + tickRate);
        }
    }

    @Test void airStuckIsFlaggedWithinTheSpan() {
        for (long frame : new long[]{4, 16, 50, 100, 200}) {
            List<Arrival> arrivals = simulate(new long[]{frame}, true, 20, 10_000, 40, 30, -1, 0, frame);
            ResponseClockReminder reminder = new ResponseClockReminder(3);
            reminder.position();
            long firstFlag = -1;
            for (Arrival arrival : arrivals) {
                if (reminder.response(arrival.time() * MS, false) > 0) {
                    firstFlag = arrival.time();
                    break;
                }
            }
            assertTrue(firstFlag > 0 && firstFlag <= 3_500, "frame " + frame + "ms flagged at " + firstFlag);
        }
    }

    @Test void airStuckKeepsBeingFlagged() {
        List<Arrival> arrivals = simulate(new long[]{16}, true, 20, 30_000, 40, 30, -1, 0, 1);
        assertTrue(flags(new ResponseClockReminder(3), arrivals) >= 9);
    }

    @Test void exemptAndDisarmedAreNeverFlagged() {
        List<Arrival> arrivals = simulate(new long[]{16}, true, 20, 30_000, 40, 30, -1, 0, 2);
        ResponseClockReminder exempt = new ResponseClockReminder(3);
        exempt.position();
        ResponseClockReminder disarmed = new ResponseClockReminder(3);
        disarmed.disarm();
        for (Arrival arrival : arrivals) {
            assertEquals(0, exempt.response(arrival.time() * MS, true));
            assertEquals(0, disarmed.response(arrival.time() * MS, false));
        }
    }

    @Test void spanHasAFloorAndFollowsTheTickRate() {
        assertEquals(2_000_000_000L, new ResponseClockReminder(0.5).requiredSpanNanos());
        ResponseClockReminder reminder = new ResponseClockReminder(3);
        reminder.setTickRate(100);
        assertEquals(3_000_000_000L, reminder.requiredSpanNanos());
        reminder.setTickRate(10);
        assertEquals(6_000_000_000L, reminder.requiredSpanNanos());
    }
}
