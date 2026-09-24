package dev.aeroac.platform.fabric.utils.thread;

import dev.aeroac.AeroAPI;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class FabricFutureUtil {
    public static <U> CompletableFuture<U> supplySync(Supplier<U> entityTeleportSupplier) {
        CompletableFuture<U> ret = new CompletableFuture<>();
        AeroAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().run(AeroAPI.INSTANCE.getGrimPlugin(),
                () -> ret.complete(entityTeleportSupplier.get()));
        return ret;
    }
}
