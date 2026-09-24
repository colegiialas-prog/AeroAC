package dev.aeroac.neural.enforcement;

import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.DominantSignal;
import dev.aeroac.neural.risk.RiskState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate in front of the one path that can remove a player.
 *
 * <p>Most of what is asserted here is what must not happen: no verdict below the configured state,
 * none without enough behind it, none twice inside the cooldown, none carried out in announce mode
 * without a person, none carried out twice, and none quietly dropped once a person confirmed it.
 * Every test runs through the presenter and executor seams, so nothing here needs a server.
 */
class BanServiceTest {

    private static AdminPlayerView view(UUID id, RiskState state, int evidence, int predictions) {
        return new AdminPlayerView(id, "Suspect", state, 14.2, 15.0, 3, 40, true,
                0.96, 0.94, Double.NaN, Double.NaN, new DominantSignal("AIM", 0.94),
                "flash", "v2", true, 50, 12, predictions, 40, 300, evidence, true,
                7, 1.2, 0.4, 0.2, null, 0, 0, 0, false, null, new long[10]);
    }

    static ConfigManager config(Map<String, Object> values) {
        return (ConfigManager) Proxy.newProxyInstance(BanServiceTest.class.getClassLoader(),
                new Class<?>[]{ConfigManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasLoaded")) return true;
                    if (args == null || args.length < 1) return null;
                    Object value = values.get(String.valueOf(args[0]));
                    if (value != null) return value;
                    return args.length == 2 ? args[1] : null;
                });
    }

    /** Records what it was asked to show, and settles the callback like a real presenter would. */
    private static final class RecordingPresenter implements BanPresenter {
        final List<String> shown = new ArrayList<>();
        final List<Boolean> previews = new ArrayList<>();
        boolean finishImmediately = true;
        Runnable held;

        @Override public boolean available() { return true; }

        @Override public void present(BanDecision decision, BanPolicy.Animation animation, boolean preview,
                                      Runnable onFinished) {
            shown.add(decision.name());
            previews.add(preview);
            if (finishImmediately) onFinished.run();
            else held = onFinished;
        }

        @Override public void cancelAll() { }
    }

    private record Rig(BanService service, RecordingPresenter presenter, List<String> commands) { }

    private static Rig rig(Map<String, Object> settings) {
        RecordingPresenter presenter = new RecordingPresenter();
        List<String> commands = new ArrayList<>();
        BanService service = new BanService();
        service.presenter(presenter);
        service.executor(commands::add);
        service.reload(BanPolicy.read(config(settings)));
        return new Rig(service, presenter, commands);
    }

    private static Map<String, Object> mode(String mode) {
        return Map.of("neural.enforcement.mode", mode);
    }

    // ---- the gate --------------------------------------------------------------------------

    @Test void theDefaultIsToAnnounceRatherThanBan() {
        BanPolicy policy = BanPolicy.read(config(Map.of()));
        assertEquals(BanPolicy.Mode.ANNOUNCE, policy.mode(),
                "an uncalibrated detector must not ban on its own by default");
        assertEquals(RiskState.CONFIRMED, policy.minState());
    }

    @Test void nothingBelowTheConfiguredBarProducesAVerdict() {
        Rig rig = rig(mode("announce"));
        UUID id = UUID.randomUUID();
        assertNull(rig.service().observe(view(id, RiskState.SUSPICIOUS, 40, 40), 1000),
                "SUSPICIOUS is below the configured CONFIRMED");
        assertNull(rig.service().observe(view(id, RiskState.CONFIRMED, 2, 40), 1000),
                "not enough evidence behind it");
        assertNull(rig.service().observe(view(id, RiskState.CONFIRMED, 40, 1), 1000),
                "the model has barely seen this player");
        assertTrue(rig.service().pending().isEmpty());
        assertTrue(rig.commands().isEmpty());
    }

    @Test void offDecidesNothingAtAll() {
        Rig rig = rig(mode("off"));
        assertNull(rig.service().observe(view(UUID.randomUUID(), RiskState.CONFIRMED, 99, 99), 1000));
        assertTrue(rig.service().pending().isEmpty());
        assertTrue(rig.commands().isEmpty());
    }

    @Test void oneFightProducesOneVerdictNotTen() {
        Rig rig = rig(Map.of("neural.enforcement.mode", "announce",
                "neural.enforcement.cooldown-seconds", 600));
        UUID id = UUID.randomUUID();
        assertNotNull(rig.service().observe(view(id, RiskState.CONFIRMED, 40, 40), 1000));
        for (int i = 0; i < 20; i++) {
            assertNull(rig.service().observe(view(id, RiskState.CONFIRMED, 40 + i, 40 + i), 2000 + i),
                    "the cooldown must hold for the whole fight");
        }
        assertEquals(1, rig.service().pending().size());
    }

    // ---- announce --------------------------------------------------------------------------

    @Test void announceQueuesAVerdictAndRunsNothingUntilAPersonAnswers() {
        Rig rig = rig(mode("announce"));
        BanDecision decision = rig.service().observe(view(UUID.randomUUID(), RiskState.CONFIRMED, 40, 40), 1000);
        assertNotNull(decision);
        assertEquals(1, rig.service().pending().size());
        assertTrue(rig.presenter().shown.isEmpty(), "announce must not start the send-off by itself");
        assertTrue(rig.commands().isEmpty(), "announce must not ban by itself");

        assertNull(rig.service().confirm("nonsense", "tester"), "an unknown id does nothing");
        assertTrue(rig.commands().isEmpty());

        assertNotNull(rig.service().confirm(decision.id(), "tester"));
        assertTrue(rig.service().pending().isEmpty());
        assertEquals(List.of("Suspect"), rig.presenter().shown);
        assertEquals(List.of(false), rig.presenter().previews, "a real ban is never a preview");
        assertEquals(List.of("ban Suspect Aero AC: unfair advantage"), rig.commands());
    }

    @Test void decliningLeavesThePlayerAlone() {
        Rig rig = rig(mode("announce"));
        BanDecision decision = rig.service().observe(view(UUID.randomUUID(), RiskState.CONFIRMED, 40, 40), 1000);
        assertNotNull(rig.service().deny(decision.id(), "tester"));
        assertTrue(rig.service().pending().isEmpty());
        assertTrue(rig.presenter().shown.isEmpty());
        assertTrue(rig.commands().isEmpty());
        assertNull(rig.service().deny(decision.id(), "tester"), "the same verdict cannot be answered twice");
        assertNull(rig.service().confirm(decision.id(), "tester"), "nor confirmed after a refusal");
        assertTrue(rig.commands().isEmpty());
    }

    @Test void anUnansweredVerdictExpiresIntoNothing() {
        Rig rig = rig(Map.of("neural.enforcement.mode", "announce",
                "neural.enforcement.confirm-timeout-seconds", 60));
        BanDecision decision = rig.service().observe(view(UUID.randomUUID(), RiskState.CONFIRMED, 40, 40), 1000);
        rig.service().expire(30_000);
        assertEquals(1, rig.service().pending().size(), "still inside the window");

        rig.service().expire(1000 + 61_000);
        assertTrue(rig.service().pending().isEmpty());
        assertNull(rig.service().confirm(decision.id(), "tester"), "an expired verdict cannot be confirmed");
        assertTrue(rig.commands().isEmpty(), "silence is a refusal, not a delayed ban");
    }

    /**
     * The confirmed case is the one that was shown, not the one the numbers drifted to.
     *
     * <p>Between the announcement and somebody pressing confirm the player keeps playing and risk
     * decays. Approving what was on screen and executing something else is how staff stop trusting
     * a tool.
     */
    @Test void theVerdictIsFrozenWhenItIsMade() {
        Rig rig = rig(mode("announce"));
        BanDecision decision = rig.service().observe(view(UUID.randomUUID(), RiskState.CONFIRMED, 40, 40), 1000);
        assertEquals(RiskState.CONFIRMED, decision.state());
        assertEquals(14.2, decision.risk(), 1e-9);
        assertEquals(40, decision.evidence());
        assertEquals(decision, rig.service().find(decision.id()));
    }

    // ---- automatic -------------------------------------------------------------------------

    @Test void automaticCarriesOutWithoutAnybodyAnswering() {
        Rig rig = rig(mode("automatic"));
        assertNotNull(rig.service().observe(view(UUID.randomUUID(), RiskState.CONFIRMED, 40, 40), 1000));
        assertTrue(rig.service().pending().isEmpty(), "automatic mode queues nothing");
        assertEquals(List.of("Suspect"), rig.presenter().shown);
        assertEquals(1, rig.commands().size());
    }

    @Test void theBanWaitsForTheSendOffToFinish() {
        Rig rig = rig(mode("automatic"));
        rig.presenter().finishImmediately = false;
        rig.service().observe(view(UUID.randomUUID(), RiskState.CONFIRMED, 40, 40), 1000);
        assertTrue(rig.commands().isEmpty(), "the ban lands after the show, not during it");
        rig.presenter().held.run();
        assertEquals(1, rig.commands().size());
    }

    /** The one path that removes a player must not remove them twice, whatever the presenter does. */
    @Test void aPresenterThatCallsBackTwiceStillBansOnce() {
        Rig rig = rig(mode("automatic"));
        rig.presenter().finishImmediately = false;
        rig.service().observe(view(UUID.randomUUID(), RiskState.CONFIRMED, 40, 40), 1000);
        Runnable callback = rig.presenter().held;
        callback.run();
        callback.run();
        callback.run();
        assertEquals(1, rig.commands().size());
    }

    /** A second verdict must not start a second flight for a player already being sent off. */
    @Test void aPlayerMidFlightIsNotDecidedOnAgain() {
        Rig rig = rig(Map.of("neural.enforcement.mode", "automatic",
                "neural.enforcement.cooldown-seconds", 1));
        rig.presenter().finishImmediately = false;
        UUID id = UUID.randomUUID();
        assertNotNull(rig.service().observe(view(id, RiskState.CONFIRMED, 40, 40), 1000));
        assertNull(rig.service().observe(view(id, RiskState.CONFIRMED, 41, 41), 100_000),
                "the flight is still running; a second one must not begin");
        assertEquals(1, rig.presenter().shown.size());
    }

    // ---- preview ---------------------------------------------------------------------------

    @Test void aPreviewPlaysTheShowAndBansNobody() {
        Rig rig = rig(mode("automatic"));
        boolean[] finished = {false};
        rig.service().preview(new BanDecision("preview", UUID.randomUUID(), "Admin",
                RiskState.CONFIRMED, 0, Double.NaN, null, 0, 0, 1), () -> finished[0] = true);
        assertTrue(finished[0]);
        assertEquals(List.of(true), rig.presenter().previews, "the presenter is told it is a preview");
        assertTrue(rig.commands().isEmpty(), "a preview never dispatches a command");
        assertTrue(rig.service().pending().isEmpty());
    }

    // ---- the command -----------------------------------------------------------------------

    @Test void theCommandIsBuiltFromTheTemplate() {
        BanPolicy policy = BanPolicy.read(config(Map.of(
                "neural.enforcement.command", "/tempban {player} 30d {reason}",
                "neural.enforcement.reason", "Aero: unfair advantage")));
        assertEquals("tempban Suspect 30d Aero: unfair advantage", policy.commandFor("Suspect"),
                "a leading slash is stripped; the console does not want one");
    }

    /**
     * A name is the one value in the command that did not come from the operator.
     *
     * <p>Vanilla names cannot contain spaces or separators, but this runs on servers that are not
     * vanilla, and a name that smuggled in a second command would run it as the console.
     */
    @Test void aHostileNameCannotBecomeASecondCommand() {
        BanPolicy policy = BanPolicy.read(config(Map.of()));
        String command = policy.commandFor("evil; op attacker");
        assertFalse(command.contains(";"));
        assertFalse(command.contains(" op "));
        assertTrue(command.startsWith("ban evilopattacker "));
    }

    @Test void thresholdsAreClampedRatherThanTrusted() {
        BanPolicy policy = BanPolicy.read(config(Map.of(
                "neural.enforcement.min-evidence", -5,
                "neural.enforcement.animation.seconds", 9999,
                "neural.enforcement.animation.levitation-amplifier", 500)));
        assertEquals(1, policy.minEvidence());
        assertEquals(30, policy.animation().seconds());
        assertEquals(10, policy.animation().levitation());
    }
}
