package ac.grim.grimac.neural.admin;

import ac.grim.grimac.neural.risk.RiskState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Alerts fire on a climb, once per throttle, and a fall is recorded without announcing itself. */
class AdminAlertsTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final AdminConfig.Alerts SETTINGS =
            new AdminConfig.Alerts(true, RiskState.WATCH, 30);

    private static AdminPlayerView at(RiskState state, double overall) {
        return new AdminPlayerView(PLAYER, "Tester", state, 7.82, 8.0, 1, 5, true,
                overall, 0.91, 0.32, 0.04,
                new DominantSignal("AIM", 0.91), "flash", "v1", true, 10, 5, 4,
                43, 92, 14, true, -1, Double.NaN, Double.NaN, Double.NaN,
                null, 0, 0, 0, false, null, new long[10]);
    }

    @Test void firesOnceWhenTheStateClimbs() {
        AdminAlerts alerts = new AdminAlerts();
        assertNull(alerts.observe(at(RiskState.CLEAN, 0.1), SETTINGS, 0),
                "starting clean is not a transition");

        AdminAlerts.Alert alert = alerts.observe(at(RiskState.SUSPICIOUS, 0.91), SETTINGS, 1000);
        assertNotNull(alert);
        assertEquals(RiskState.CLEAN, alert.from());
        assertEquals(RiskState.SUSPICIOUS, alert.to());
        assertEquals("AIM", alert.dominant());

        assertNull(alerts.observe(at(RiskState.SUSPICIOUS, 0.92), SETTINGS, 2000),
                "the same state must not alert again");
    }

    @Test void throttlesRepeatedClimbsForOnePlayer() {
        AdminAlerts alerts = new AdminAlerts();
        alerts.observe(at(RiskState.CLEAN, 0.1), SETTINGS, 0);
        assertNotNull(alerts.observe(at(RiskState.WATCH, 0.5), SETTINGS, 1_000));
        // Climbs again well inside the 30s throttle.
        assertNull(alerts.observe(at(RiskState.SUSPICIOUS, 0.9), SETTINGS, 5_000));
        // ...and again once the throttle has passed.
        assertNotNull(alerts.observe(at(RiskState.CONFIRMED, 0.99), SETTINGS, 40_000));
    }

    /** A player oscillating around the boundary must not alert on every crossing. */
    @Test void aFallIsRecordedSilentlyAndDoesNotReAlertImmediately() {
        AdminAlerts alerts = new AdminAlerts();
        alerts.observe(at(RiskState.CLEAN, 0.1), SETTINGS, 0);
        assertNotNull(alerts.observe(at(RiskState.WATCH, 0.5), SETTINGS, 1_000));
        assertNull(alerts.observe(at(RiskState.CLEAN, 0.1), SETTINGS, 2_000), "falling is quiet");
        assertNull(alerts.observe(at(RiskState.WATCH, 0.5), SETTINGS, 3_000),
                "climbing back inside the throttle stays quiet");
    }

    @Test void respectsTheMinimumStateAndTheMasterSwitch() {
        AdminAlerts high = new AdminAlerts();
        AdminConfig.Alerts onlyConfirmed = new AdminConfig.Alerts(true, RiskState.CONFIRMED, 1);
        high.observe(at(RiskState.CLEAN, 0.1), onlyConfirmed, 0);
        assertNull(high.observe(at(RiskState.SUSPICIOUS, 0.9), onlyConfirmed, 1_000));
        assertNotNull(high.observe(at(RiskState.CONFIRMED, 0.99), onlyConfirmed, 5_000));

        AdminAlerts off = new AdminAlerts();
        AdminConfig.Alerts disabled = new AdminConfig.Alerts(false, RiskState.WATCH, 1);
        off.observe(at(RiskState.CLEAN, 0.1), disabled, 0);
        assertNull(off.observe(at(RiskState.CONFIRMED, 0.99), disabled, 1_000));
    }

    @Test void mutingIsPerAdministratorAndReversible() {
        AdminAlerts alerts = new AdminAlerts();
        UUID admin = UUID.randomUUID();
        assertFalse(alerts.muted(admin));
        alerts.toggle(admin, false);
        assertTrue(alerts.muted(admin));
        alerts.toggle(admin, true);
        assertFalse(alerts.muted(admin));
    }

    @Test void forgettingAPlayerResetsTheirTransitionHistory() {
        AdminAlerts alerts = new AdminAlerts();
        alerts.observe(at(RiskState.CLEAN, 0.1), SETTINGS, 0);
        assertNotNull(alerts.observe(at(RiskState.WATCH, 0.5), SETTINGS, 1_000));
        alerts.forget(PLAYER);
        // After a quit and a rejoin the throttle no longer applies: it is a new session.
        assertNotNull(alerts.observe(at(RiskState.WATCH, 0.5), SETTINGS, 2_000));
    }

    @Test void theAlertTextNeverClaimsAProbabilityOfCheating() {
        AdminAlerts alerts = new AdminAlerts();
        alerts.observe(at(RiskState.CLEAN, 0.1), SETTINGS, 0);
        Component rendered = AdminAlerts.render(alerts.observe(at(RiskState.SUSPICIOUS, 0.91), SETTINGS, 1_000));
        String text = flatten(rendered);
        assertTrue(text.contains("Tester"));
        assertTrue(text.contains("SUSPICIOUS"));
        assertTrue(text.contains("AI Risk 91%"));
        assertTrue(text.contains("[PROFILE]"));
        assertTrue(text.contains("[WATCH]"));
        assertFalse(text.toLowerCase().contains("chance of cheating"));
    }

    /** Flattens a component tree to its text, so the assertions read what an operator would see. */
    private static String flatten(Component component) {
        StringBuilder text = new StringBuilder();
        if (component instanceof TextComponent plain) text.append(plain.content());
        for (Component child : component.children()) text.append(flatten(child));
        return text.toString();
    }

    @Test void aMissingPredictionShowsAsNoDataInsideAnAlert() {
        AdminAlerts alerts = new AdminAlerts();
        alerts.observe(at(RiskState.CLEAN, Double.NaN), SETTINGS, 0);
        Component rendered = AdminAlerts.render(
                alerts.observe(at(RiskState.WATCH, Double.NaN), SETTINGS, 1_000));
        String text = flatten(rendered);
        assertTrue(text.contains("AI Risk " + AdminStyle.NO_DATA));
        assertFalse(text.contains("AI Risk 0%"));
    }
}
