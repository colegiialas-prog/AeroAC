package dev.aeroac.checks;

import dev.aeroac.AeroAPI;
import ac.grim.grimac.api.AbstractProcessor;
import ac.grim.grimac.api.config.ConfigReloadable;
import dev.aeroac.utils.common.ConfigReloadObserver;

public abstract class AeroProcessor implements AbstractProcessor, ConfigReloadable, ConfigReloadObserver {

    // Not everything has to be a check for it to process packets & be configurable

    @Override
    public void reload() {
        reload(AeroAPI.INSTANCE.getConfigManager().getConfig());
    }

}
