package dev.aeroac.neural.enforcement;

import dev.aeroac.AeroAPI;
import dev.aeroac.locale.AeroMessages;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.risk.RiskState;
import dev.aeroac.platform.api.player.PlatformPlayer;
import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.utils.anticheat.LogUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether the anticheat asks for a ban, and carries the answer out.
 *
 * <p>It reads the same published snapshot every screen reads and applies a gate: the reported state,
 * how much evidence has accumulated, and how much the model has actually seen. It computes nothing
 * of its own. That matters more here than anywhere else in the interface — this is the one path
 * that can remove a player, so it must not be able to reach a conclusion the rest of the system did
 * not already reach and show.
 *
 * <p>Two modes and one rule about them. In ANNOUNCE the verdict is written to staff with the
 * numbers behind it and waits for a person; in AUTOMATIC it runs on its own. The verdict is frozen
 * when it is made, so a confirmation half an hour later executes the case that was shown to the
 * person confirming it and not whatever the risk value has drifted to since.
 *
 * <p>Every decision, confirmation, refusal and expiry is written to the log with the numbers. A
 * punishment nobody can reconstruct afterwards is a punishment nobody can defend.
 */
public final class BanService {
    private volatile BanPolicy policy = BanPolicy.read(EmptyConfig.INSTANCE);
    private volatile BanPresenter presenter = BanPresenter.IMMEDIATE;

    /** Verdicts waiting for a person, by id. */
    private final Map<String, BanDecision> pending = new ConcurrentHashMap<>();
    /** When each player was last decided on, so one fight does not produce ten verdicts. */
    private final Map<UUID, Long> decided = new ConcurrentHashMap<>();
    /** Players whose send-off is running; a second decision must not start a second flight. */
    private final Map<UUID, Boolean> running = new ConcurrentHashMap<>();
    /** Automatic verdicts waiting for the next wave. A disconnect does not clear them. */
    private final BanWave wave = new BanWave(() -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());

    /** Uniform [0,1); a seam so tests can pin the wave jitter. */
    public void random(java.util.function.DoubleSupplier replacement) {
        wave.random(replacement == null ? () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble() : replacement);
    }

    public void reload(BanPolicy replacement) {
        policy = replacement;
        if (!replacement.automatic()) {
            // A switch away from automatic bans also withdraws the ones still waiting for a wave.
            wave.clear().forEach(decision -> LogUtil.info("Aero enforcement " + decision.id()
                    + " withdrawn from the wave by reload: " + decision.name()));
        }
        pending.clear();
        decided.clear();
        presenter.cancelAll();
        running.clear();
    }

    public void presenter(BanPresenter replacement) {
        presenter = replacement == null ? BanPresenter.IMMEDIATE : replacement;
    }

    public BanPolicy policy() { return policy; }

    public List<BanDecision> pending() { return List.copyOf(pending.values()); }

    /**
     * One player, one refresh. Returns the verdict when the gate opens, and null the rest of the
     * time, which is almost always.
     */
    public BanDecision observe(AdminPlayerView view, long nowMillis) {
        BanPolicy current = policy;
        if (view == null || view.uuid() == null || current.mode() == BanPolicy.Mode.OFF) return null;
        RiskState state = view.state();
        if (!current.admits(state, view.evidenceCount(), view.predictionCount())) return null;
        if (running.containsKey(view.uuid())) return null;

        Long last = decided.get(view.uuid());
        if (last != null && nowMillis - last < current.cooldownSeconds() * 1000L) return null;
        decided.put(view.uuid(), nowMillis);

        BanDecision decision = new BanDecision(BanDecision.nextId(view.uuid(), nowMillis), view.uuid(),
                view.name(), state, view.risk(), view.overall(),
                view.dominant() == null ? null : view.dominant().label(),
                view.evidenceCount(), view.predictionCount(), nowMillis);

        LogUtil.info("Aero enforcement " + current.mode() + " " + decision.id() + ": " + decision.name()
                + " state=" + state + " risk=" + AdminStyle.number(decision.risk(), 2)
                + " model=" + AdminStyle.percent(decision.overall())
                + " evidence=" + decision.evidence() + " predictions=" + decision.predictions());

        if (current.waves()) {
            wave.queue(decision, nowMillis, current.waveMinutes());
            LogUtil.info("Aero enforcement " + decision.id() + " queued for the ban wave: " + decision.name());
        } else if (current.automatic()) {
            carryOut(decision, AeroMessages.tr("ban.by_automatic"));
        } else {
            pending.put(decision.id(), decision);
        }
        return decision;
    }

    /** Automatic verdicts waiting for the next wave. */
    public List<BanDecision> queued() { return wave.queued(); }

    /** When the next wave runs, or 0 when nothing waits. */
    public long nextWaveMillis() { return wave.nextMillis(); }

    /**
     * Carries out every queued verdict once the wave is due. Returns how many ran. Called on the same
     * refresh that expires unanswered verdicts; a player who left in the meantime is banned anyway.
     */
    public int releaseWave(long nowMillis) {
        List<BanDecision> due = wave.release(nowMillis);
        if (!due.isEmpty()) LogUtil.info("Aero enforcement ban wave: " + due.size() + " verdict(s)");
        for (BanDecision decision : due) carryOut(decision, AeroMessages.tr("ban.by_automatic"));
        return due.size();
    }

    /** Drops verdicts nobody answered. An unanswered verdict is a refusal, not a delayed ban. */
    public void expire(long nowMillis) {
        releaseWave(nowMillis);
        int timeout = policy.confirmTimeoutSeconds();
        pending.values().removeIf(decision -> {
            if (!decision.expired(nowMillis, timeout)) return false;
            LogUtil.info("Aero enforcement " + decision.id() + " expired unanswered: " + decision.name());
            return true;
        });
    }

    /** Confirms one verdict. Returns the decision that ran, or null when there was nothing to run. */
    public BanDecision confirm(String id, String by) {
        BanDecision decision = id == null ? null : pending.remove(id.toLowerCase(java.util.Locale.ROOT));
        if (decision == null) return null;
        carryOut(decision, by);
        return decision;
    }

    /** Declines one verdict. The player is left alone and the refusal is recorded. */
    public BanDecision deny(String id, String by) {
        BanDecision decision = id == null ? null : pending.remove(id.toLowerCase(java.util.Locale.ROOT));
        if (decision == null) return null;
        LogUtil.info("Aero enforcement " + decision.id() + " declined by " + by + ": " + decision.name());
        return decision;
    }

    public BanDecision find(String id) {
        return id == null ? null : pending.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public void forget(UUID player) {
        decided.remove(player);
        running.remove(player);
        pending.values().removeIf(decision -> decision.uuid().equals(player));
    }

    /**
     * Runs the send-off and then the ban command.
     *
     * <p>The command is dispatched from the console on the global scheduler, because that is where
     * a command may be dispatched, and after the animation rather than during it: a ban that lands
     * mid-flight kicks the player and the rest of the show plays to an empty seat.
     */
    public void carryOut(BanDecision decision, String by) {
        BanPolicy current = policy;
        running.put(decision.uuid(), Boolean.TRUE);
        // Once and only once, whatever the presenter does. The contract says it calls back exactly
        // once; this is the one path in the plugin that removes a player, so the contract is not
        // the only thing standing between a presenter bug and a double ban.
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable ban = () -> {
            if (!done.compareAndSet(false, true)) return;
            running.remove(decision.uuid());
            String command = current.commandFor(decision.name());
            LogUtil.info("Aero enforcement " + decision.id() + " carried out by " + by
                    + ": " + decision.name() + " -> /" + command);
            dispatch(command);
        };
        if (current.animation().enabled() && presenter.available()) {
            presenter.present(decision, current.animation(), false, ban);
        } else {
            ban.run();
        }
    }

    /**
     * Plays the send-off and stops there.
     *
     * <p>For tuning the sounds and the timing without removing anybody: no command is dispatched,
     * no cooldown is recorded, and nothing is written to the log as a verdict, because none was
     * reached. It is the animation and nothing else.
     */
    public void preview(BanDecision decision, Runnable onFinished) {
        BanPolicy current = policy;
        if (!current.animation().enabled() || !presenter.available()) {
            onFinished.run();
            return;
        }
        presenter.present(decision, current.animation(), true, onFinished);
    }

    /**
     * What actually runs the ban.
     *
     * <p>A seam for the same reason the presenter is one: without it the decision reaches into the
     * server singleton, and the one class that can remove a player becomes the one class that
     * cannot be exercised without a running server. An implementation is expected to hop to
     * whichever thread its platform dispatches commands on.
     */
    public interface BanExecutor {
        void execute(String command);
    }

    private volatile BanExecutor executor = BanService::throughConsole;

    public void executor(BanExecutor replacement) {
        executor = replacement == null ? BanService::throughConsole : replacement;
    }

    private void dispatch(String command) {
        try {
            executor.execute(command);
        } catch (RuntimeException | LinkageError error) {
            LogUtil.warn("Aero enforcement command failed: /" + command + " -> " + error);
        }
    }

    /** The default: the console, on the scheduler thread a command may be dispatched from. */
    private static void throughConsole(String command) {
        Runnable run = () -> {
            try {
                Sender console = AeroAPI.INSTANCE.getPlatformServer().getConsoleSender();
                AeroAPI.INSTANCE.getPlatformServer().dispatchCommand(console, command);
            } catch (RuntimeException error) {
                LogUtil.warn("Aero enforcement command failed: /" + command + " -> " + error);
            }
        };
        try {
            AeroAPI.INSTANCE.getScheduler().getGlobalRegionScheduler()
                    .execute(AeroAPI.INSTANCE.getGrimPlugin(), run);
        } catch (RuntimeException unavailable) {
            run.run();
        }
    }

    /** Sends one verdict to everybody who may answer it. */
    public void announce(BanDecision decision, String permission) {
        Component message = render(decision);
        for (var player : AeroAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            PlatformPlayer platform = player.platformPlayer;
            if (platform == null || !platform.hasPermission(permission)) continue;
            platform.sendMessage(message);
        }
        AeroAPI.INSTANCE.getPlatformServer().getConsoleSender().sendMessage(message);
    }

    /**
     * {@code [Aero] Аероу забанил бы X — но не банит без подтверждения}, with the numbers and two
     * buttons. Worded as a conditional on purpose: nothing has happened to the player yet.
     */
    public static Component render(BanDecision decision) {
        Component headline = Component.text("[Aero] ", NamedTextColor.DARK_AQUA)
                .append(Component.text(AeroMessages.tr("ban.would_ban", decision.name()), NamedTextColor.WHITE));

        Component detail = Component.text("  " + AeroMessages.tr("ban.state") + ": ", NamedTextColor.GRAY)
                .append(Component.text(decision.state().name(), NamedTextColor.RED))
                .append(Component.text("   " + AeroMessages.tr("ban.risk") + " "
                        + AdminStyle.number(decision.risk(), 2), NamedTextColor.GRAY))
                .append(Component.text("   " + AdminStyle.RISK_LABEL + " "
                        + AdminStyle.percent(decision.overall())
                        + (decision.dominant() == null ? "" : " | " + decision.dominant()), NamedTextColor.GRAY))
                .append(Component.text("   " + AeroMessages.tr("ban.evidence") + " " + decision.evidence()
                        + " / " + AeroMessages.tr("ban.predictions") + " " + decision.predictions(),
                        NamedTextColor.DARK_GRAY));

        Component actions = Component.text("  ", NamedTextColor.DARK_GRAY)
                .append(button("[" + AeroMessages.tr("ban.confirm") + "]",
                        "/aero ban confirm " + decision.id(),
                        AeroMessages.tr("ban.confirm_hover", decision.name()), NamedTextColor.RED))
                .append(Component.text("  "))
                .append(button("[" + AeroMessages.tr("ban.deny") + "]", "/aero ban deny " + decision.id(),
                        AeroMessages.tr("ban.deny_hover"), NamedTextColor.GREEN))
                .append(Component.text("  "))
                .append(button("[" + AeroMessages.tr("ban.profile") + "]",
                        "/aero player " + decision.name(),
                        AeroMessages.tr("ban.profile_hover"), NamedTextColor.AQUA));

        return headline.append(Component.newline()).append(detail)
                .append(Component.newline()).append(actions);
    }

    private static Component button(String label, String command, String tooltip, NamedTextColor colour) {
        return Component.text(label, colour)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(tooltip, NamedTextColor.GRAY)));
    }

    /** Text form of the pending queue, for the console and for a screen that lists them. */
    public List<String> describePending() {
        List<String> lines = new ArrayList<>();
        for (BanDecision decision : pending.values()) {
            lines.add(decision.id() + "  " + decision.name() + "  " + decision.state()
                    + "  " + AdminStyle.RISK_LABEL + " " + AdminStyle.percent(decision.overall())
                    + "  " + AeroMessages.tr("ban.risk") + " " + AdminStyle.number(decision.risk(), 2));
        }
        return lines;
    }

    /** Defaults before the first reload, so a getter during start-up is never a null policy. */
    private static final class EmptyConfig {
        static final ac.grim.grimac.api.config.ConfigManager INSTANCE =
                (ac.grim.grimac.api.config.ConfigManager) java.lang.reflect.Proxy.newProxyInstance(
                        BanService.class.getClassLoader(),
                        new Class<?>[]{ac.grim.grimac.api.config.ConfigManager.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("hasLoaded")) return false;
                            if (args != null && args.length == 2) return args[1];
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == double.class) return 0d;
                            if (type == java.util.List.class) return java.util.List.of();
                            if (type == java.util.Map.class) return java.util.Map.of();
                            return null;
                        });
    }
}
