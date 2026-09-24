package dev.aeroac.neural.admin;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.inference.ModelKind;
import dev.aeroac.neural.inference.PredictionResult;
import dev.aeroac.neural.risk.RiskState;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sorting, pagination, colour and the one rule the whole interface rests on: a value the model
 * never produced is never rendered as a number.
 */
class AdminViewRenderingTest {

    private static AdminPlayerView view(String name, RiskState state, double overall, double risk) {
        return new AdminPlayerView(UUID.nameUUIDFromBytes(name.getBytes()), name, state, risk, risk, 0, 0, true,
                overall, Double.NaN, Double.NaN, Double.NaN, null, "flash", "v1", true,
                10, 5, overall > 0 || !Double.isNaN(overall) ? 1 : 0, 40, 30, 3, true,
                -1, Double.NaN, Double.NaN, Double.NaN, null, 0, 0, 0, false, null, new long[10]);
    }

    @Test void sortsByAccumulatedRiskEvenWhenModelOutputsDisagree() {
        AdminSnapshot snapshot = AdminSnapshot.of(List.of(
                view("low", RiskState.CLEAN, 0.99, 1.0),
                view("high", RiskState.SUSPICIOUS, 0.10, 7.8),
                view("middle", RiskState.WATCH, 0.55, 3.0)), 1);
        assertEquals(List.of("high", "middle", "low"),
                snapshot.players().stream().map(AdminPlayerView::name).toList());
    }

    /**
     * A player with no prediction sorts below a player the model scored at zero.
     *
     * <p>The two are different claims and the ordering has to keep them apart: "scored 0%" is
     * evidence of nothing happening, "never scored" is the absence of evidence.
     */
    @Test void unscoredPlayersSortBelowScoredZero() {
        AdminSnapshot snapshot = AdminSnapshot.of(List.of(
                view("unscored", RiskState.CLEAN, Double.NaN, 0),
                view("scoredZero", RiskState.CLEAN, 0.0, 0)), 1);
        assertEquals("scoredZero", snapshot.players().get(0).name());
        assertFalse(snapshot.players().get(1).hasPrediction());
    }

    @Test void countsAndFiltersByState() {
        AdminSnapshot snapshot = AdminSnapshot.of(List.of(
                view("a", RiskState.CLEAN, 0.1, 0),
                view("b", RiskState.WATCH, 0.4, 2.5),
                view("c", RiskState.CONFIRMED, 0.99, 14),
                view("d", RiskState.MITIGATED, 0.8, 9)), 1);
        assertEquals(1, snapshot.count(RiskState.CLEAN));
        assertEquals(1, snapshot.count(RiskState.WATCH));
        assertEquals(3, snapshot.suspicious().size());
        assertEquals("c", snapshot.findByName("C").name());
        assertNull(snapshot.findByName("nobody"));
    }

    @Test void pagesClampInsteadOfFailing() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 47; i++) items.add(i);

        AdminSnapshot.Page<Integer> first = AdminSnapshot.page(items, 0, 45);
        assertEquals(45, first.items().size());
        assertEquals(2, first.pages());
        assertTrue(first.hasNext());
        assertFalse(first.hasPrevious());

        AdminSnapshot.Page<Integer> second = AdminSnapshot.page(items, 1, 45);
        assertEquals(2, second.items().size());
        assertFalse(second.hasNext());

        // A page that fell off the end while the list shrank must not produce an empty screen.
        AdminSnapshot.Page<Integer> beyond = AdminSnapshot.page(items, 99, 45);
        assertEquals(1, beyond.index());
        assertEquals(2, beyond.items().size());

        AdminSnapshot.Page<Integer> empty = AdminSnapshot.page(List.of(), 3, 45);
        assertEquals(0, empty.index());
        assertEquals(1, empty.pages());
        assertTrue(empty.items().isEmpty());
    }

    @Test void oneColourPerState() {
        assertEquals(NamedTextColor.GREEN, AdminStyle.colour(RiskState.CLEAN));
        assertEquals(NamedTextColor.YELLOW, AdminStyle.colour(RiskState.WATCH));
        assertEquals(NamedTextColor.GOLD, AdminStyle.colour(RiskState.SUSPICIOUS));
        assertEquals(NamedTextColor.RED, AdminStyle.colour(RiskState.MITIGATED));
        assertEquals(NamedTextColor.DARK_RED, AdminStyle.colour(RiskState.CONFIRMED));
        assertEquals("●", AdminStyle.symbol(RiskState.CLEAN));
        assertEquals("⚠", AdminStyle.symbol(RiskState.SUSPICIOUS));
    }

    @Test void missingValuesRenderAsNoDataNeverZero() {
        assertEquals(AdminStyle.NO_DATA, AdminStyle.percent(Double.NaN));
        assertEquals("0%", AdminStyle.percent(0.0));
        assertEquals("87%", AdminStyle.percent(0.87));
        assertEquals(AdminStyle.NO_DATA, AdminStyle.number(Double.NaN, 2));
        assertEquals(AdminStyle.NO_DATA, AdminStyle.duration(-1));
        assertEquals("--:--", AdminStyle.clock(-1));
        assertNotEquals("0%", AdminStyle.percent(Double.NaN));
    }

    @Test void wordsThePercentageAsAModelOutput() {
        assertEquals(AeroMessages.tr("admin.ai_risk"), AdminStyle.RISK_LABEL);
        assertFalse(AdminStyle.RISK_LABEL.toLowerCase().contains("chance"));
        assertFalse(AdminStyle.RISK_LABEL.toLowerCase().contains("probability"));
    }

    @Test void formatsDurationsForPeople() {
        assertEquals("42" + AeroMessages.tr("admin.s"), AdminStyle.duration(42));
        assertEquals("1" + AeroMessages.tr("admin.m") + "32" + AeroMessages.tr("admin.s"),
                AdminStyle.duration(92));
        assertEquals("2ч 5м ", AdminStyle.duration(7500));
        assertEquals("03:42", AdminStyle.clock(222));
    }

    @Test void dominantHeadPicksTheLoudestSpecificOne() {
        PredictionResult aim = prediction(new String[]{"overall", "aimAssist", "killAura"},
                new double[]{0.9, 0.93, 0.2});
        assertEquals(AeroMessages.tr("admin.aim"), DominantSignal.of(aim).label());
        assertEquals(0.93, DominantSignal.of(aim).value(), 1e-9);

        PredictionResult aura = prediction(new String[]{"overall", "aimAssist", "killAura"},
                new double[]{0.9, 0.2, 0.71});
        assertEquals("AURA", DominantSignal.of(aura).label());

        PredictionResult trigger = prediction(new String[]{"overall", "triggerBot"},
                new double[]{0.6, 0.66});
        assertEquals(AeroMessages.tr("admin.trigger"), DominantSignal.of(trigger).label());
    }

    @Test void dominantHeadFallsBackToGenericAndToNothing() {
        PredictionResult overallOnly = prediction(new String[]{"overall"}, new double[]{0.84});
        DominantSignal generic = DominantSignal.of(overallOnly);
        assertEquals(DominantSignal.GENERIC, generic.label());
        assertTrue(generic.generic());

        assertNull(DominantSignal.of(null), "no prediction must produce no signal, not a 0% one");
        assertNull(DominantSignal.of(prediction(new String[]{"somethingElse"}, new double[]{0.5})));
    }

    private static PredictionResult prediction(String[] names, double[] values) {
        return new PredictionResult(1, System.nanoTime(), ModelKind.FLASH, "v1", true, names, values, 12);
    }

    @Test void viewModesFilterByState() {
        assertFalse(AdminViewMode.OFF.shows(RiskState.CONFIRMED));
        assertTrue(AdminViewMode.ALL.shows(RiskState.CLEAN));
        assertFalse(AdminViewMode.SUSPICIOUS.shows(RiskState.CLEAN));
        assertTrue(AdminViewMode.SUSPICIOUS.shows(RiskState.WATCH));
        assertFalse(AdminViewMode.AUTO.shows(RiskState.CLEAN));
        assertTrue(AdminViewMode.AUTO.shows(RiskState.MITIGATED));
        assertEquals(AdminViewMode.AUTO, AdminViewMode.parse("AuTo", AdminViewMode.OFF));
        assertEquals(AdminViewMode.OFF, AdminViewMode.parse("nonsense", AdminViewMode.OFF));
    }

    /**
     * Cleanup bookkeeping. Sending is not exercised here: a packet needs a live connection, and
     * what has to be right without one is that nothing is remembered about a viewer or a target
     * who has gone.
     */
    @Test void overlayForgetsViewersAndTargets() {
        NameplateOverlay overlay = new NameplateOverlay();
        UUID admin = UUID.randomUUID();
        assertEquals(0, overlay.viewers());
        overlay.forget(admin);
        overlay.removeTarget(null, admin, UUID.randomUUID());
        assertEquals(0, overlay.tagsFor(admin));
        assertTrue(overlay.tagged(admin).isEmpty());
    }
}
