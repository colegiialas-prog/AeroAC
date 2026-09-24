package dev.aeroac.platform.fabric.player;

import dev.aeroac.platform.api.player.OfflinePlatformPlayer;
import dev.aeroac.platform.fabric.AbstractAeroACFabricEntryPoint;
import dev.aeroac.platform.fabric.inject.FabricMinecraftServerHandle;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

@RequiredArgsConstructor
@Getter
public class FabricOfflinePlatformPlayer implements OfflinePlatformPlayer {
    private final @NotNull UUID uniqueId;
    private final @NotNull String name;

    @Override
    public boolean isOnline() {
        FabricMinecraftServerHandle server = AbstractAeroACFabricEntryPoint.serverOrNull();
        return server != null && server.isPlayerOnline(uniqueId);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OfflinePlatformPlayer player && this.getUniqueId().equals(player.getUniqueId());
    }
}
