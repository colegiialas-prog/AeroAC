package dev.aeroac.utils.anticheat;

import dev.aeroac.AeroAPI;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.api.event.events.GrimQuitEvent;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.reflection.GeyserUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.player.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    // Holder — PlayerDataManager is constructed inside AeroAPI's ctor, so a
    // plain static-final would see a null AeroAPI.INSTANCE. Holder init runs
    // on first fire, after AeroAPI is fully built.
    private static final class Channels {
        static final GrimJoinEvent.Channel JOIN = AeroAPI.INSTANCE.getEventBus().get(GrimJoinEvent.class);
        static final GrimQuitEvent.Channel QUIT = AeroAPI.INSTANCE.getEventBus().get(GrimQuitEvent.class);
    }

    private final Set<User> exemptUsers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<User, AeroPlayer> playerDataMap = new ConcurrentHashMap<>();

    public boolean isExemptUser(@Nullable User user) {
        return user != null && exemptUsers.contains(user);
    }

    public void exemptUser(@Nullable User user) {
        if (user == null) return;
        exemptUsers.add(user);
    }

    public boolean clearExemptions(@Nullable User user) {
        if (user == null) return false;
        return exemptUsers.remove(user);
    }

    @Nullable
    public AeroPlayer getPlayer(final @NotNull UUID uuid) {
        // Is it safe to interact with this, or is this internal PacketEvents code?
        Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(uuid);
        if (channel == null) return null;
        User user = PacketEvents.getAPI().getProtocolManager().getUser(channel);
        if (user == null) return null;
        return getPlayer(user);
    }

    @Nullable
    public AeroPlayer getPlayer(final @NotNull User user) {
        @Nullable AeroPlayer player = playerDataMap.get(user);
        if (player != null && player.platformPlayer != null && player.platformPlayer.isExternalPlayer())
            return null;
        return player;
    }

    public boolean shouldCheck(@NotNull User user) {
        if (isExemptUser(user)) return false;
        if (!ChannelHelper.isOpen(user.getChannel())) return false;

        if (user.getUUID() != null) {
            // Bedrock players don't have Java movement
            if (GeyserUtil.isBedrockPlayer(user.getUUID())) {
                exemptUser(user);
                return false;
            }

            // Has exempt permission
            AeroPlayer grimPlayer = AeroAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
            if (grimPlayer != null && grimPlayer.hasPermission("grim.exempt")) {
                exemptUser(user);
                return false;
            }

            // Geyser formatted player string
            // This will never happen for Java players, as the first character in the 3rd group is always 4 (xxxxxxxx-xxxx-4xxx-xxxx-xxxxxxxxxxxx)
            if (user.getUUID().toString().startsWith("00000000-0000-0000-0009")) {
                exemptUser(user);
                return false;
            }
        }

        return true;
    }

    public void addUser(final @NotNull User user) {
        if (shouldCheck(user)) {
            AeroPlayer player = new AeroPlayer(user);
            playerDataMap.put(user, player);
            Channels.JOIN.fire(player);
        }
    }

    public AeroPlayer remove(final @NotNull User user) {
        return playerDataMap.remove(user);
    }

    public void onDisconnect(User user) {
        AeroPlayer grimPlayer = remove(user);
        if (grimPlayer != null) {
            AeroAPI.INSTANCE.getNeuralManager().disconnect(grimPlayer);
            // The quitting player may have been both a tracked target and an administrator holding
            // indicators of their own; both sides are dropped here so nothing outlives the session.
            AeroAPI.INSTANCE.getAdminService().onQuit(grimPlayer);
        }
        if (grimPlayer != null) Channels.QUIT.fire(grimPlayer);
        clearExemptions(user);

        UUID uuid = user.getProfile().getUUID();

        // All cleanup paths should call onDisconnect; routing the session-close + toggle
        // eviction here means a stuck PE event (or a JVM-level channel
        // close that doesn't surface as UserDisconnectEvent) doesn't leak an open session.
        // hooks/toggles are NOOP when the datastore is disabled or its init failed
        // AND go NOOP mid-session if an operator runs /aero reload after flipping database.enabled to false
        // a player who joined under the prior (enabled) config and disconnects post-reload has no live writer to fire onQuit, so their session stays open (row closed_at IS NULL).
        // The next datastore-enabled boot's crash sweep stamps closed_at = last_activity for still-open rows; permanently-disabled-after-the-fact leaves the row untouched until DB is enabled again.
        AeroAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks()
                .onQuitFromUserDisconnect(user, grimPlayer, System.currentTimeMillis());
        if (uuid != null) {
            AeroAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore().evict(uuid);
        }

        // Check if calling async is safe
        if (uuid == null)
            return; // folia doesn't like null getPlayer()

        // The platform player may already be gone on quit; looking it up again by UUID threw (upstream #2792).
        dev.aeroac.platform.api.player.PlatformPlayer quittingPlayer =
                dev.aeroac.platform.api.player.PlatformPlayerCache.getInstance().getPlayer(uuid);
        if (quittingPlayer != null) {
            AeroAPI.INSTANCE.getAlertManager().handlePlayerQuit(quittingPlayer);
        }

        AeroAPI.INSTANCE.getSpectateManager().onQuit(uuid);

        // TODO (Cross-platform) confirm this is 100% correct and will always remove players from cache when necessary
        AeroAPI.INSTANCE.getPlatformPlayerFactory().invalidatePlayer(uuid);
    }

    public Collection<AeroPlayer> getEntries() {
        return playerDataMap.values();
    }

    public int size() {
        return playerDataMap.size();
    }
}
