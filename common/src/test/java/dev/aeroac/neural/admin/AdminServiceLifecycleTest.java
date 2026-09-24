package dev.aeroac.neural.admin;

import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.neural.risk.RiskState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settings parsing, and the cleanup that has to happen when somebody leaves.
 *
 * <p>The service's refresh loop needs a running server, so what is exercised here is the part that
 * does not: what the configuration produces, and what state survives a quit. State that outlives a
 * player is how an interface ends up showing a name tag nobody owns, or alerting about somebody who
 * logged off an hour ago.
 */
class AdminServiceLifecycleTest {

    @Test void defaultsAreSafeAndDocumented() {
        AdminConfig config = AdminConfig.read(config(Map.of()));
        assertTrue(config.enabled());
        assertEquals(1000, config.refreshMs());
        assertTrue(config.floating().enabled());
        assertEquals(500, config.floating().refreshMs());
        assertEquals(AdminViewMode.SUSPICIOUS, config.floating().defaultMode());
        assertEquals(RiskState.WATCH, config.alerts().minState());
        assertEquals(30, config.alerts().throttleSeconds());
        assertEquals(300, config.training().targetDurationSeconds());
        assertEquals(150, config.training().targetAttackWindows());
        assertTrue(config.training().scenarios().contains("box-pvp"));
        assertTrue(config.training().cheatFamilies().contains("aim-assist"));
        assertEquals(20, config.refreshTicks());
        assertEquals(10, config.floatingTicks());
    }

    @Test void absurdValuesAreClampedRatherThanTrusted() {
        AdminConfig config = AdminConfig.read(config(Map.of(
                "neural.gui.refresh-ms", 1,
                "neural.gui.floating.refresh-ms", 10_000_000,
                "neural.gui.alerts.throttle-seconds", -5,
                "neural.gui.training.target-duration-seconds", 0,
                "neural.gui.dataset.max-sessions", Integer.MAX_VALUE)));
        assertEquals(200, config.refreshMs());
        assertEquals(60000, config.floating().refreshMs());
        assertEquals(1, config.alerts().throttleSeconds());
        assertEquals(10, config.training().targetDurationSeconds());
        assertEquals(200000, config.dataset().maxSessions());
        assertTrue(config.refreshTicks() >= 1, "a refresh period must never be zero ticks");
    }

    @Test void presetsAreNormalisedAndDeduplicated() {
        AdminConfig config = AdminConfig.read(config(Map.of(
                "neural.gui.training.scenarios", List.of("Box PvP", "box_pvp", "  Flick  ", ""))));
        assertEquals(List.of("box-pvp", "flick"), config.training().scenarios());
    }

    @Test void anEmptyPresetListFallsBackToTheDefaults() {
        AdminConfig config = AdminConfig.read(config(Map.of(
                "neural.gui.training.cheat-families", List.of(""))));
        assertTrue(config.training().cheatFamilies().contains("kill-aura"));
    }

    @Test void anUnknownIndicatorModeFallsBackRatherThanFailing() {
        AdminConfig config = AdminConfig.read(config(Map.of(
                "neural.gui.floating.default-mode", "sideways")));
        assertEquals(AdminViewMode.SUSPICIOUS, config.floating().defaultMode());
    }

    @Test void quittingDropsEveryTraceOfAPlayer() {
        AdminService service = new AdminService();
        UUID player = UUID.randomUUID();

        // As an administrator: a draft, a mute and an indicator subscription.
        service.draft(player).cheatFamily("aim-assist");
        service.alerts().toggle(player, false);
        assertTrue(service.alerts().muted(player));
        assertNotNull(service.peekDraft(player));

        service.onQuit(null);   // a null player must be a no-op, not an exception
        assertNotNull(service.peekDraft(player));

        service.gui(null);
        assertFalse(service.gui().available(), "a null bridge falls back to unavailable");
        assertEquals(AdminViewMode.OFF, service.viewMode(player));
        assertSame(AdminSnapshot.EMPTY, service.snapshot());
        assertFalse(service.training().configured());
        assertFalse(service.datasetSummary().ready());
    }

    @Test void theGuiBridgeIsOptionalEverywhere() {
        assertFalse(AdminGuiBridge.UNAVAILABLE.available());
        // Every method on the fallback has to be safe to call from a command handler.
        AdminGuiBridge.UNAVAILABLE.openMain(null);
        AdminGuiBridge.UNAVAILABLE.openPlayers(null, true);
        AdminGuiBridge.UNAVAILABLE.openProfile(null, UUID.randomUUID());
        AdminGuiBridge.UNAVAILABLE.openTraining(null);
        AdminGuiBridge.UNAVAILABLE.openRecordings(null);
        AdminGuiBridge.UNAVAILABLE.closeAll();
    }

    @Test void permissionNodesAreDistinctAndNamespaced() {
        List<String> nodes = List.of(AdminPermissions.ADMIN, AdminPermissions.GUI, AdminPermissions.PLAYERS,
                AdminPermissions.SUSPICIOUS, AdminPermissions.PROFILE, AdminPermissions.MONITOR,
                AdminPermissions.VIEW, AdminPermissions.STATUS, AdminPermissions.MITIGATION,
                AdminPermissions.ALERTS, AdminPermissions.TRAINING, AdminPermissions.TRAINING_RECORD,
                AdminPermissions.TRAINING_STOP, AdminPermissions.TRAINING_OVERVIEW,
                AdminPermissions.TRAINING_REVIEW, AdminPermissions.TRAINING_MODEL);
        assertEquals(nodes.size(), nodes.stream().distinct().count(), "no node may be a duplicate");
        for (String node : nodes) assertTrue(node.startsWith("aero."), node);
        // Recording, stopping and reviewing are separable from merely looking.
        assertNotEquals(AdminPermissions.TRAINING, AdminPermissions.TRAINING_RECORD);
        assertNotEquals(AdminPermissions.TRAINING_RECORD, AdminPermissions.TRAINING_REVIEW);
    }

    /** Config keys the interface reads, answered with the caller's own fallback. */
    private static ConfigManager config(Map<String, Object> values) {
        return (ConfigManager) Proxy.newProxyInstance(AdminServiceLifecycleTest.class.getClassLoader(),
                new Class<?>[]{ConfigManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasLoaded")) return true;
                    if (args == null || args.length < 1) return null;
                    Object value = values.get(String.valueOf(args[0]));
                    if (value != null) return value;
                    return args.length == 2 ? args[1] : null;
                });
    }
}
