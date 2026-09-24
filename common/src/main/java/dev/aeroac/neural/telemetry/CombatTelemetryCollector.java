package dev.aeroac.neural.telemetry;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.NeuralConfig;
import dev.aeroac.neural.dataset.DatasetSession;
import dev.aeroac.neural.target.AimErrorCalculator;
import dev.aeroac.neural.target.TargetTracker;
import dev.aeroac.neural.window.TemporalRingBuffer;
import dev.aeroac.neural.window.AttackWindowBuilder;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.collisions.datatypes.SimpleCollisionBox;
import dev.aeroac.utils.data.packetentity.PacketEntity;
import dev.aeroac.utils.math.Vector3dm;
import dev.aeroac.utils.math.VectorUtils;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.Arrays;

import static dev.aeroac.neural.telemetry.FrameField.*;

/**
 * All methods run on the player's packet event loop. Never reads platform/Bukkit entity state.
 * A dataset session is optional: telemetry also feeds inference and risk with nothing recorded.
 */
public final class CombatTelemetryCollector {
    private final AeroPlayer player;
    private final NeuralConfig config;
    private final long generation;
    private final double[] scratch = new double[FrameField.COUNT];
    public final TemporalRingBuffer frames;
    private final TargetTracker target = new TargetTracker();
    // Only close()/detach cross threads; recording is attached and detached on the event loop.
    private volatile DatasetSession session;
    private long tick;
    private long previousNanos;
    private long lastAttackNanos;
    private long lastCombatNanos;
    private long lastAttackTick = -1;
    private long lastAttackWindowTick = -1;
    private double attackInterval = Double.NaN;
    private double yaw, pitch, deltaYaw, deltaPitch, acceleration;
    private double targetX, targetY, targetZ, previousAimError = Double.NaN;
    private int previousTarget = -1;
    private int attacks, swings, cancelledAttacks;
    private int reachFlags, wallFlags, pierceFlags, orderFlags;
    private int lastTransaction = -1;
    private double previousPing = Double.NaN, jitter;
    private ReachObservation reach;
    private boolean discontinuity = true;
    private int rotationSamples;
    // Operator counters. Read-only totals for the administrator interface: unlike the fields above
    // they survive a discontinuity, because an operator recording a session wants the session total,
    // not the total since the last teleport. Nothing downstream reads them.
    private final long startNanos;
    private long lifetimeAttacks;
    private long lifetimeSwings;
    private long lifetimeAttackWindows;
    private long recordingMovementGaps;
    private long observedCombatNanos;
    private boolean previousCombat;

    public long recordingMovementGaps() { return recordingMovementGaps; }
    public long combatSeconds() { return observedCombatNanos / 1_000_000_000L; }

    public CombatTelemetryCollector(AeroPlayer player, NeuralConfig config, long generation, long nowNanos) {
        this.player = player;
        this.config = config;
        this.generation = generation;
        this.lastCombatNanos = nowNanos;
        this.startNanos = nowNanos;
        frames = new TemporalRingBuffer(config.continuousSize());
    }

    public NeuralConfig config() { return config; }
    /** Increments on every reload, so a response built for an older configuration is discarded. */
    public long generation() { return generation; }
    public long tick() { return tick; }
    public DatasetSession session() { return session; }
    public int currentTarget() { return target.current(); }
    public long startNanos() { return startNanos; }
    public long lifetimeAttacks() { return lifetimeAttacks; }
    public long lifetimeSwings() { return lifetimeSwings; }
    public long lifetimeAttackWindows() { return lifetimeAttackWindows; }
    public boolean idle(long nowNanos, long timeoutNanos) {
        return session == null && nowNanos - lastCombatNanos > timeoutNanos;
    }

    public void attach(DatasetSession replacement) {
        session = replacement;
        lifetimeAttacks = lifetimeSwings = lifetimeAttackWindows = recordingMovementGaps = 0;
    }
    public void detach() { session = null; }

    private void offer(TelemetryRecord record) {
        DatasetSession current = session;
        if (current == null) return;
        if (!current.accepting()) {
            session = null;
            return;
        }
        current.offer(record);
    }

    public void attack(int id, boolean cancelled, long now) {
        attacks++;
        if (session != null && session.accepting()) lifetimeAttacks++;
        lastCombatNanos = now;
        if (cancelled) cancelledAttacks++;
        attackInterval = lastAttackNanos == 0 ? Double.NaN : (now - lastAttackNanos) / 1_000_000.0;
        lastAttackNanos = now;
        lastAttackTick = tick + 1;
        PacketEntity entity = player.compensatedEntities.entityMap.get(id);
        if (validTarget(entity) && id != player.entityID && !player.inVehicle()
                && !player.getSetbackTeleportUtil().shouldBlockMovement()) {
            target.attack(id, entity, tick + 1, now);
        }
        offer(new CombatEvent(now, tick, "attack", id, cancelled, player.yaw, player.pitch));
    }

    public void swing(boolean cancelled, long now) {
        swings++;
        if (session != null && session.accepting()) lifetimeSwings++;
        offer(new CombatEvent(now, tick, "swing", -1, cancelled, player.yaw, player.pitch));
    }

    public void flag(String name) {
        if (name == null) return;
        if (name.equals("Reach")) reachFlags++;
        else if (name.equals("WallHit")) wallFlags++;
        else if (name.equals("EntityPierce")) pierceFlags++;
        else if (name.startsWith("PacketOrder")) orderFlags++;
        else return;
        offer(new CombatEvent(System.nanoTime(), tick, "flag:" + name, -1, false, player.yaw, player.pitch));
    }

    public void reach(ReachObservation observation) {
        // Last result is operational context only; every result is also retained as a raw event.
        reach = observation;
        offer(observation);
    }

    public void discontinuity(String reason) {
        if ("movementGap".equals(reason) && session != null && session.accepting()) recordingMovementGaps++;
        previousCombat = false;
        discontinuity = true;
        rotationSamples = 0;
        attacks = swings = cancelledAttacks = reachFlags = wallFlags = pierceFlags = orderFlags = 0;
        lastAttackTick = -1;
        lastAttackWindowTick = -1;
        lastAttackNanos = 0;
        attackInterval = Double.NaN;
        target.clear();
        reach = null;
        previousTarget = -1;
        previousAimError = Double.NaN;
        frames.clear();
        offer(new CombatEvent(System.nanoTime(), tick, reason, -1, false, player.yaw, player.pitch));
    }

    public void sample(PacketReceiveEvent packet) {
        long now = System.nanoTime();
        if (!Double.isFinite(player.yaw) || !Double.isFinite(player.pitch)
                || !Double.isFinite(player.x + player.y + player.z)) {
            discontinuity("invalidMovement");
            return;
        }
        if (previousNanos != 0 && now - previousNanos > 150_000_000L) {
            discontinuity("movementGap");
            // The marker was timestamped inside discontinuity(); the following frame must be later.
            now = System.nanoTime();
        }
        tick++;
        Arrays.fill(scratch, Double.NaN);
        put(YAW, player.yaw); put(PITCH, player.pitch);
        double dyaw = AimErrorCalculator.normalizeYaw(player.yaw - yaw);
        double dpitch = player.pitch - pitch;
        double acc = Math.hypot(dyaw - deltaYaw, dpitch - deltaPitch);
        if (!discontinuity) {
            put(DELTA_YAW, dyaw); put(DELTA_PITCH, dpitch);
            put(ROTATION_SPEED, Math.hypot(dyaw, dpitch));
            if (rotationSamples >= 2) {
                put(DELTA2_YAW, dyaw - deltaYaw); put(DELTA2_PITCH, dpitch - deltaPitch);
                put(ROTATION_ACCELERATION, acc);
            }
            if (rotationSamples >= 3) put(ROTATION_JERK, acc - acceleration);
        }
        put(PLAYER_X, player.x); put(PLAYER_Y, player.y); put(PLAYER_Z, player.z);
        put(EYE_HEIGHT, player.getEyeHeight());
        put(VELOCITY_X, player.clientVelocity.getX()); put(VELOCITY_Y, player.clientVelocity.getY()); put(VELOCITY_Z, player.clientVelocity.getZ());
        put(ON_GROUND, player.packetStateData.packetPlayerOnGround); put(AIRBORNE, !player.packetStateData.packetPlayerOnGround);
        put(SPRINTING, player.isSprinting); put(SNEAKING, player.isSneaking);
        put(TELEPORT_STATE, player.packetStateData.lastPacketWasTeleport || player.getSetbackTeleportUtil().shouldBlockMovement());
        put(VEHICLE_STATE, player.inVehicle()); put(INVENTORY_STATE, player.inventory.openWindowID != 0);
        put(HELD_ITEM_TYPE, player.inventory.getHeldItem().getType().getId(player.getClientVersion()));
        PacketEntity entity = player.compensatedEntities.entityMap.get(target.current());
        boolean present = target.validate(entity, validTarget(entity) && !player.inVehicle()
                && !player.getSetbackTeleportUtil().shouldBlockMovement(), tick, now, config.targetTimeoutTicks());
        put(TARGET_PRESENT, present); put(TARGET_ENTITY_ID, target.current()); put(PREVIOUS_TARGET_ENTITY_ID, target.previous());
        boolean switched = target.consumeSwitch();
        put(TARGET_SWITCH, switched); put(TICKS_SINCE_TARGET_SWITCH, target.ticksSinceSwitch(tick));
        if (switched) previousTarget = -1;
        if (present) targetGeometry(entity);
        else { previousTarget = -1; previousAimError = Double.NaN; }
        put(ATTACK, attacks > 0); put(SWING, swings > 0);
        put(ATTACK_COUNT, attacks); put(SWING_COUNT, swings); put(CANCELLED_ATTACK_COUNT, cancelledAttacks);
        put(TICKS_SINCE_ATTACK, lastAttackTick < 0 ? Double.NaN : tick - lastAttackTick);
        put(ATTACK_INTERVAL_MS, attackInterval);
        int transaction = player.getLastTransactionReceived();
        double ping = player.getTransactionPing();
        if (transaction > 0 && transaction != lastTransaction) {
            if (Double.isFinite(previousPing)) jitter += (Math.abs(ping - previousPing) - jitter) / 16.0;
            previousPing = ping; lastTransaction = transaction;
        }
        if (transaction > 0) { put(PING_MS, ping); put(ESTIMATED_JITTER_MS, jitter); }
        put(TRANSACTION_ID, transaction);
        put(SERVER_TICK_DURATION_MS, AeroAPI.INSTANCE.getNeuralManager().serverTickDurationMs());
        put(SERVER_TICK, AeroAPI.INSTANCE.getNeuralManager().serverTickNumber());
        put(CLIENT_PROTOCOL_VERSION, player.getClientVersion().getProtocolVersion());
        put(REACH_EVIDENCE, reachFlags); put(WALL_HIT_EVIDENCE, wallFlags); put(ENTITY_PIERCE_EVIDENCE, pierceFlags); put(PACKET_ORDER_EVIDENCE, orderFlags);
        if (reach != null) {
            put(REACH_TARGET_ENTITY_ID, reach.entityId());
            put(REACH_OBSERVATION_AGE_MS, (now - reach.nanoTime()) / 1_000_000.0);
            put(REACH_DISTANCE, reach.distance());
            // Multi-ray intersection is not a measurement of the current single crosshair.
            // LOS is valid only for an observation in this sample interval and this target.
            if (reach.entityId() == target.current() && reach.nanoTime() > previousNanos && reach.lineOfSight() >= 0) {
                put(LINE_OF_SIGHT, reach.lineOfSight());
            }
        }
        boolean flying = WrapperPlayClientPlayerFlying.isFlying(packet.getPacketType());
        if (flying) {
            WrapperPlayClientPlayerFlying movement = new WrapperPlayClientPlayerFlying(packet);
            put(MOVEMENT_HAS_POSITION, movement.hasPositionChanged()); put(MOVEMENT_HAS_LOOK, movement.hasRotationChanged());
        } else { put(MOVEMENT_HAS_POSITION, false); put(MOVEMENT_HAS_LOOK, false); }
        put(SEGMENT_START, discontinuity);
        put(SAMPLE_INTERVAL_MS, previousNanos == 0 ? Double.NaN : (now - previousNanos) / 1_000_000.0);
        CombatFrame frame = new CombatFrame(tick, now, scratch);
        frames.add(frame);
        if (previousCombat && previousNanos > 0 && now - previousNanos <= 150_000_000L) {
            observedCombatNanos += Math.min(50_000_000L, Math.max(0L, now - previousNanos));
        }
        previousCombat = present || attacks > 0;
        // UI progress is independent of whether either model requests attack windows.
        // Gaps clear the ring, so the endpoints and the anchor suffice; no window allocation.
        DatasetSession recording = session;
        int windowLength = config.attackBefore() + 1 + config.attackAfter();
        if (recording != null && recording.accepting() && frames.size() >= windowLength) {
            CombatFrame first = frames.get(frames.size() - windowLength);
            CombatFrame anchor = frames.get(frames.size() - 1 - config.attackAfter());
            if (first.nanoTime() >= recording.metadata.startNanos()
                    && first.tick() + windowLength - 1 == frame.tick() && anchor.value(ATTACK) == 1) {
                lifetimeAttackWindows++;
            }
        }
        offer(frame);
        previousNanos = now; yaw = player.yaw; pitch = player.pitch;
        deltaYaw = discontinuity ? 0 : dyaw; deltaPitch = discontinuity ? 0 : dpitch; acceleration = discontinuity ? 0 : acc;
        discontinuity = false;
        rotationSamples = Math.min(4, rotationSamples + 1);
        if (attacks > 0 || present) lastCombatNanos = now;
        attacks = swings = cancelledAttacks = reachFlags = wallFlags = pierceFlags = orderFlags = 0;
    }

    private void targetGeometry(PacketEntity entity) {
        SimpleCollisionBox box = entity.getPossibleCollisionBoxes();
        put(TARGET_MIN_X, box.minX); put(TARGET_MIN_Y, box.minY); put(TARGET_MIN_Z, box.minZ);
        put(TARGET_MAX_X, box.maxX); put(TARGET_MAX_Y, box.maxY); put(TARGET_MAX_Z, box.maxZ);
        double x = (box.minX + box.maxX) * 0.5, y = box.minY, z = (box.minZ + box.maxZ) * 0.5;
        put(TARGET_X, x); put(TARGET_Y, y); put(TARGET_Z, z);
        put(TARGET_TYPE, entity.getType().getId(player.getClientVersion()));
        if (previousTarget == target.current() && !discontinuity) {
            put(TARGET_VELOCITY_X, x - targetX); put(TARGET_VELOCITY_Y, y - targetY); put(TARGET_VELOCITY_Z, z - targetZ);
        }
        Vector3dm point = VectorUtils.cutBoxToVector(player.x, player.y + player.getEyeHeight(), player.z, box);
        double dx = point.getX() - player.x, dy = point.getY() - player.y - player.getEyeHeight(), dz = point.getZ() - player.z;
        put(AIM_POINT_X, point.getX()); put(AIM_POINT_Y, point.getY()); put(AIM_POINT_Z, point.getZ());
        put(DISTANCE_TO_TARGET, Math.sqrt(dx * dx + dy * dy + dz * dz));
        AimErrorCalculator.AimError aim = AimErrorCalculator.calculate(player.yaw, player.pitch, dx, dy, dz);
        put(TARGET_YAW, aim.targetYaw()); put(TARGET_PITCH, aim.targetPitch());
        put(AIM_ERROR_YAW, aim.yaw()); put(AIM_ERROR_PITCH, aim.pitch()); put(AIM_ERROR_TOTAL, aim.total());
        if (previousTarget == target.current() && !discontinuity) put(AIM_ERROR_DELTA, aim.total() - previousAimError);
        previousTarget = target.current(); targetX = x; targetY = y; targetZ = z; previousAimError = aim.total();
    }

    private static boolean validTarget(PacketEntity entity) {
        return entity != null && entity.isLivingEntity && !entity.isDead && entity.riding == null;
    }

    /**
     * The attack window that became complete with the sample just taken, or null. Each attack is
     * offered at most once, so a rate limiter downstream never sees the same window twice.
     */
    public CombatFrame[] completedAttackWindow() {
        int anchorIndex = frames.size() - 1 - config.attackAfter();
        if (anchorIndex < config.attackBefore()) return null;
        CombatFrame anchor = frames.get(anchorIndex);
        if (anchor.value(ATTACK) != 1 || anchor.tick() <= lastAttackWindowTick) return null;
        CombatFrame[] window = AttackWindowBuilder.extract(frames, anchor.tick(), config.attackBefore(), config.attackAfter());
        if (window == null) return null;
        lastAttackWindowTick = anchor.tick();
        return window;
    }

    /** The most recent uninterrupted run of samples, or null when the history is short or broken. */
    public CombatFrame[] continuousWindow(int length) {
        if (length < 1 || frames.size() < length) return null;
        CombatFrame[] window = frames.tail(length);
        long first = window[0].tick();
        for (int i = 1; i < length; i++) {
            if (window[i].tick() != first + i || window[i].value(SEGMENT_START) == 1) return null;
        }
        return window;
    }

    /** Copies at most count frames ending at the newest sample. Used for operator snapshots. */
    public CombatFrame[] recentFrames(int count) {
        return frames.tail(Math.min(Math.max(0, count), frames.size()));
    }

    /** On-demand command inspection only; no overlapping window allocations on the packet hot path. */
    public CombatFrame[] latestAttackWindow() {
        for (int i = frames.size() - 1 - config.attackAfter(); i >= config.attackBefore(); i--) {
            CombatFrame frame = frames.get(i);
            if (frame.value(ATTACK) == 1) {
                return AttackWindowBuilder.extract(frames, frame.tick(), config.attackBefore(), config.attackAfter());
            }
        }
        return null;
    }

    private void put(FrameField field, double value) { scratch[field.ordinal()] = value; }
    private void put(FrameField field, boolean value) { put(field, value ? 1 : 0); }
}
