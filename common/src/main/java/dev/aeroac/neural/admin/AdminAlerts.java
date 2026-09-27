package dev.aeroac.neural.admin;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.risk.RiskState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns risk state transitions into one operator alert.
 *
 * <p>An alert is a transition, never a prediction. The model produces an output every window and
 * the risk engine moves continuously; an alert on either would be a stream nobody reads, and a
 * stream nobody reads is the same as no alerting at all. So: only when the reported state actually
 * climbs, only at or above the configured state, and at most once per throttle per player.
 *
 * <p>A state that falls is recorded silently. That matters — without it, a player oscillating
 * around the WATCH boundary would alert on every crossing.
 */
public final class AdminAlerts {
    private final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();
    /** Administrators who turned alerts off for this session. Absence means "on". */
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    private record Tracked(RiskState state, long alertedAtMillis) { }

    /** One alert ready to send, or null when this change is not worth anyone's attention. */
    public record Alert(UUID uuid, String name, RiskState from, RiskState to, double risk,
                        double overall, String dominant) { }

    /**
     * Records the player's current state and returns an alert when the change deserves one.
     *
     * <p>Called once per refresh with the same snapshot the interface renders, so an alert can
     * never describe a state no screen ever showed.
     */
    public Alert observe(AdminPlayerView view, AdminConfig.Alerts config, long nowMillis) {
        if (view == null || view.uuid() == null || !view.neuralEnabled()) return null;
        RiskState now = view.state() == null ? RiskState.CLEAN : view.state();
        Tracked previous = tracked.get(view.uuid());
        RiskState before = previous == null ? RiskState.CLEAN : previous.state();

        long alertedAt = previous == null ? 0 : previous.alertedAtMillis();
        if (now == before) {
            // Record the first sighting, so a later fall has something to fall from.
            if (previous == null) tracked.put(view.uuid(), new Tracked(now, alertedAt));
            return null;
        }
        boolean climbed = now.ordinal() > before.ordinal();
        boolean loud = config != null && config.enabled() && climbed && now.atLeast(config.minState());
        // A player who has never alerted is not throttled: the throttle is a gap between alerts,
        // not a delay before the first one.
        boolean throttled = alertedAt > 0 && config != null
                && nowMillis - alertedAt < config.throttleSeconds() * 1000L;

        if (!loud || throttled) {
            // Still record the new state: a fall that is not announced must not alert on the way back.
            tracked.put(view.uuid(), new Tracked(now, alertedAt));
            return null;
        }
        tracked.put(view.uuid(), new Tracked(now, nowMillis));
        return new Alert(view.uuid(), view.name(), before, now, view.risk(), view.overall(),
                view.dominant() == null ? null : view.dominant().label());
    }

    public void forget(UUID player) { tracked.remove(player); }

    public void reset() { tracked.clear(); }

    public boolean muted(UUID admin) { return muted.contains(admin); }

    /** Returns the new state, so a command can report it without asking again. */
    public boolean toggle(UUID admin, boolean enabled) {
        if (enabled) muted.remove(admin); else muted.add(admin);
        return enabled;
    }

    public void forgetViewer(UUID admin) { muted.remove(admin); }

    /**
     * {@code [Aero] Player -> SUSPICIOUS} with the numbers behind it and two clickable actions.
     *
     * <p>The percentage is labelled AI Risk, not a chance of cheating: it is an experimental model
     * output that has not been calibrated on a real labelled dataset, and an alert is exactly where
     * that distinction stops being academic.
     */
    public static Component render(Alert alert) {
        Component headline = Component.text("[Aero] ", NamedTextColor.DARK_AQUA)
                .append(Component.text(alert.name() == null ? "?" : alert.name(), NamedTextColor.WHITE))
                .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                .append(Component.text(AdminLabels.state(alert.to()), AdminStyle.colour(alert.to())));

        Component detail = Component.text(AeroMessages.tr("admin.risk") + AdminStyle.number(alert.risk(), 2), NamedTextColor.GRAY)
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text(AdminStyle.RISK_LABEL + " " + AdminStyle.percent(alert.overall()),
                        NamedTextColor.GRAY));
        if (alert.dominant() != null && !Double.isNaN(alert.overall())) {
            detail = detail.append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(alert.dominant(), AdminStyle.colour(alert.to())));
        }

        Component actions = Component.text("  ", NamedTextColor.DARK_GRAY)
                .append(action(AeroMessages.tr("admin.profile"), "/aero player " + alert.name(),
                        AeroMessages.tr("admin.open_the_full_profile_for") + alert.name(), NamedTextColor.AQUA))
                .append(Component.text(" "))
                .append(action(AeroMessages.tr("admin.watch"), "/aero watch " + alert.name(),
                        AeroMessages.tr("admin.stream_live_telemetry_for") + alert.name(), NamedTextColor.YELLOW))
                .append(Component.text(" "))
                .append(action(AeroMessages.tr("admin.teleport"), "/aero tp " + alert.name(),
                        AeroMessages.tr("admin.teleport_to") + alert.name(), NamedTextColor.GREEN))
                .append(Component.text(" "))
                .append(action(AeroMessages.tr("admin.spectate"), "/aero spectate " + alert.name(),
                        AeroMessages.tr("admin.spectate_as") + alert.name(), NamedTextColor.LIGHT_PURPLE));

        return headline.append(Component.newline()).append(detail).append(Component.newline()).append(actions);
    }

    private static Component action(String label, String command, String tooltip, NamedTextColor colour) {
        return Component.text(label, colour)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(tooltip, NamedTextColor.GRAY)));
    }
}
