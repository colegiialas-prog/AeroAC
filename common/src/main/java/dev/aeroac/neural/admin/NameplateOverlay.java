package dev.aeroac.neural.admin;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.player.AeroPlayer;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Viewer-only marker armour stands: no Bukkit entity, scoreboard membership or detector state. */
public final class NameplateOverlay {
    static final int MAX_TARGETS = 64;
    private static final AtomicInteger IDS = new AtomicInteger(-2_000_000_000);
    public record Target(AdminPlayerView view, int entityId) { }
    record Visible(AdminPlayerView view, Vector3d position) { }
    interface Sink {
        void spawn(int id, Vector3d position, Component text);
        void move(int id, Vector3d position);
        void text(int id, Component text);
        void destroy(int id);
    }
    private record Entry(int entityId, Vector3d position, Component text) { }
    private static final class Viewer {
        final String world;
        final Sink sink;
        final Map<UUID, Entry> entries = new HashMap<>();
        Viewer(String world, Sink sink) { this.world = world; this.sink = sink; }
    }
    private final Map<UUID, Viewer> shown = new HashMap<>();

    /** Called on the viewer's packet event loop; uses positions already tracked for that client. */
    public void update(AeroPlayer viewer, List<Target> targets, boolean recording) {
        if (viewer.user == null || viewer.getClientVersion().isOlderThan(ClientVersion.V_1_8)
                || viewer.user.getPacketVersion().isOlderThan(ClientVersion.V_1_8)) return;
        List<Visible> visible = new ArrayList<>();
        for (Target target : targets) {
            if (visible.size() >= MAX_TARGETS) break;
            if (target.view().uuid().equals(viewer.getUniqueId())) continue;
            var entity = viewer.compensatedEntities.entityMap.get(target.entityId());
            if (entity == null || entity.isDead || entity.getType() != EntityTypes.PLAYER) continue;
            if (entity.getUuid() != null && !entity.getUuid().equals(target.view().uuid())) continue;
            Vector3d pos = entity.trackedServerPosition.getPos();
            double dx = pos.x - viewer.x, dy = pos.y - viewer.y, dz = pos.z - viewer.z;
            if (!Double.isFinite(dx + dy + dz) || dx * dx + dy * dy + dz * dz > 64 * 64) continue;
            visible.add(new Visible(target.view(), new Vector3d(pos.x, pos.y + 2.45, pos.z)));
        }
        reconcile(viewer.getUniqueId(), String.valueOf(viewer.worldName), visible, recording, new PacketSink(viewer.user));
    }

    /** A small reconciliation seam also used by lifecycle tests, with a recording transport. */
    synchronized void reconcile(UUID viewerId, String world, List<Visible> visible, boolean recording, Sink sink) {
        Viewer state = shown.get(viewerId);
        if (state != null && !state.world.equals(world)) { clear(viewerId); state = null; }
        if (state == null) { state = new Viewer(world, sink); shown.put(viewerId, state); }
        Set<UUID> wanted = new HashSet<>();
        for (Visible item : visible) {
            UUID id = item.view().uuid();
            if (wanted.size() >= MAX_TARGETS || id.equals(viewerId) || !wanted.add(id)) continue;
            Component text = render(item.view(), recording);
            Entry old = state.entries.get(id);
            int entity = old == null ? IDS.getAndIncrement() : old.entityId();
            if (old == null) sink.spawn(entity, item.position(), text);
            else {
                if (!old.position().equals(item.position())) sink.move(entity, item.position());
                if (!old.text().equals(text)) sink.text(entity, text);
            }
            state.entries.put(id, new Entry(entity, item.position(), text));
        }
        Iterator<Map.Entry<UUID, Entry>> iterator = state.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!wanted.contains(entry.getKey())) { state.sink.destroy(entry.getValue().entityId()); iterator.remove(); }
        }
        if (state.entries.isEmpty()) shown.remove(viewerId);
    }

    public synchronized void clearViewer(User user, UUID viewerId) { clear(viewerId); }
    private void clear(UUID viewerId) {
        Viewer state = shown.remove(viewerId);
        if (state != null) for (Entry entry : state.entries.values()) state.sink.destroy(entry.entityId());
    }
    /** Only for a connection that has gone, or a client respawn that discarded its entities. */
    public synchronized void forget(UUID viewerId) { shown.remove(viewerId); }
    public synchronized void removeTarget(User user, UUID viewerId, UUID targetId) {
        Viewer state = shown.get(viewerId);
        if (state == null) return;
        Entry entry = state.entries.remove(targetId);
        if (entry != null) state.sink.destroy(entry.entityId());
        if (state.entries.isEmpty()) shown.remove(viewerId);
    }
    public synchronized int viewers() { return shown.size(); }
    public synchronized int tagsFor(UUID viewerId) { return tagged(viewerId).size(); }
    synchronized List<UUID> tagged(UUID viewerId) {
        Viewer state = shown.get(viewerId);
        return state == null ? List.of() : List.copyOf(state.entries.keySet());
    }

    static Component render(AdminPlayerView target, boolean showRecording) {
        RecordingView rec = showRecording ? target.recording() : null;
        Component line = Component.empty();
        if (rec != null) {
            String family = rec.shortLabel();
            if (family.length() > 48) family = family.substring(0, 45) + "...";
            line = Component.text("REC " + family + " " + AdminStyle.clock(rec.durationSeconds())
                    + " " + rec.attackWindows() + "w | ", rec.qualityWarning() ? NamedTextColor.GOLD : AdminStyle.colour(rec.label()));
        }
        String risk = target.hasPrediction() ? AdminStyle.percent(target.overall()) : AdminStyle.NO_DATA;
        String head = target.dominant() == null ? "AI" : target.dominant().label();
        return line.append(Component.text(AdminStyle.symbol(target.state()) + AeroMessages.tr("admin.ai_risk") + risk + " | " + head,
                AdminStyle.colour(target.state())));
    }

    static int markerIndex(ClientVersion version) {
        if (version.isOlderThan(ClientVersion.V_1_10)) return 10;
        if (version.isOlderThanOrEquals(ClientVersion.V_1_13_2)) return 11;
        if (version.isOlderThanOrEquals(ClientVersion.V_1_14_4)) return 13;
        if (version.isOlderThan(ClientVersion.V_1_17)) return 14;
        return 15;
    }

    private static final class PacketSink implements Sink {
        private final User user;
        PacketSink(User user) { this.user = user; }
        private void send(com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
            try { user.sendPacketSilently(packet); } catch (RuntimeException disconnected) { /* quit clears ownership */ }
        }
        private List<EntityData<?>> metadata(Component text) {
            ClientVersion version = user.getPacketVersion();
            List<EntityData<?>> data = new ArrayList<>();
            data.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20)); // invisible
            data.add(new EntityData<>(markerIndex(version), EntityDataTypes.BYTE, (byte) 0x10)); // no hitbox
            if (version.isNewerThanOrEquals(ClientVersion.V_1_13)) {
                data.add(new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(text)));
            } else {
                String legacy = AdventureSerializer.toLegacyFormat(text);
                data.add(new EntityData<>(2, EntityDataTypes.STRING, legacy));
            }
            if (version.isNewerThanOrEquals(ClientVersion.V_1_9)) {
                data.add(new EntityData<>(3, EntityDataTypes.BOOLEAN, true));
            } else data.add(new EntityData<>(3, EntityDataTypes.BYTE, (byte) 1));
            if (version.isNewerThanOrEquals(ClientVersion.V_1_10)) data.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true));
            return data;
        }
        @Override public void spawn(int id, Vector3d pos, Component text) {
            UUID uuid = UUID.randomUUID();
            if (user.getPacketVersion().isNewerThanOrEquals(ClientVersion.V_1_19)) {
                send(new WrapperPlayServerSpawnEntity(id, Optional.of(uuid), EntityTypes.ARMOR_STAND,
                        pos, 0, 0, 0, 0, Optional.empty()));
            } else {
                send(new WrapperPlayServerSpawnLivingEntity(id, uuid, EntityTypes.ARMOR_STAND, pos,
                        0, 0, 0, new Vector3d(), metadata(text)));
            }
            text(id, text);
        }
        @Override public void move(int id, Vector3d pos) { send(new WrapperPlayServerEntityTeleport(id, pos, 0, 0, false)); }
        @Override public void text(int id, Component text) { send(new WrapperPlayServerEntityMetadata(id, metadata(text))); }
        @Override public void destroy(int id) { send(new WrapperPlayServerDestroyEntities(id)); }
    }
}
