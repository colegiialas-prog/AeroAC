package dev.aeroac.platform.fabric.initables;

import dev.aeroac.AeroAPI;
import dev.aeroac.manager.init.start.StartableInitable;
import dev.aeroac.manager.init.stop.StoppableInitable;
import dev.aeroac.platform.fabric.utils.metrics.MetricsFabric;
import dev.aeroac.utils.anticheat.Constants;

public class FabricBStats implements StartableInitable, StoppableInitable {

    private MetricsFabric metricsFabric;

    @Override
    public void start() {
        try {
            metricsFabric = new MetricsFabric(AeroAPI.INSTANCE.getGrimPlugin(), Constants.BSTATS_PLUGIN_ID);
        } catch (Exception ignored) {}
    }

    @Override
    public void stop() {
        if (metricsFabric != null)
            metricsFabric.shutdown();
    }
}
