package dev.aeroac.neural.enforcement;

import dev.aeroac.neural.risk.RiskState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BanWaveTest {

    private static BanDecision decision(String name, long at) {
        UUID id = UUID.randomUUID();
        return new BanDecision(BanDecision.nextId(id, at), id, name, RiskState.CONFIRMED, 14, 0.95, "AIM", 40, 40, at);
    }

    @Test void theFirstVerdictSchedulesAJitteredWaveAndLaterOnesJoinIt() {
        BanWave wave = new BanWave(() -> 0.25);
        wave.queue(decision("A", 1_000), 1_000, 20);
        long due = 1_000 + (long) (20 * 60_000L * 0.75);
        assertEquals(due, wave.nextMillis());
        wave.queue(decision("B", 5_000), 5_000, 20);
        assertEquals(due, wave.nextMillis(), "joining a wave does not move it");
        assertTrue(wave.release(due - 1).isEmpty());
        assertEquals(2, wave.release(due).size());
        assertTrue(wave.queued().isEmpty());
        assertEquals(0, wave.nextMillis());
        assertTrue(wave.release(Long.MAX_VALUE / 2).isEmpty(), "a wave runs once");
    }

    @Test void theWaveTimeSpansHalfToOneAndAHalfPeriods() {
        BanWave early = new BanWave(() -> 0.0);
        early.queue(decision("A", 0), 0, 10);
        assertEquals(5 * 60_000L, early.nextMillis());
        BanWave late = new BanWave(() -> 0.999999);
        late.queue(decision("A", 0), 0, 10);
        assertTrue(late.nextMillis() < 15 * 60_000L && late.nextMillis() > 14 * 60_000L);
    }

    @Test void clearingWithdrawsEverything() {
        BanWave wave = new BanWave(() -> 0.5);
        wave.queue(decision("A", 0), 0, 5);
        assertEquals(1, wave.clear().size());
        assertEquals(0, wave.nextMillis());
        assertTrue(wave.release(Long.MAX_VALUE / 2).isEmpty());
    }
}
