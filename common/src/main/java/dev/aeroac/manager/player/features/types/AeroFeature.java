package dev.aeroac.manager.player.features.types;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.feature.FeatureState;
import dev.aeroac.player.AeroPlayer;

public interface AeroFeature {
    String getName();

    void setState(AeroPlayer player, ConfigManager config, FeatureState state);

    boolean isEnabled(AeroPlayer player);

    boolean isEnabledInConfig(AeroPlayer player, ConfigManager config);
}
