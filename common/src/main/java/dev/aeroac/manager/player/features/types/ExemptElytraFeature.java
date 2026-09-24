package dev.aeroac.manager.player.features.types;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.feature.FeatureState;
import dev.aeroac.player.AeroPlayer;

public class ExemptElytraFeature implements AeroFeature {

    @Override
    public String getName() {
        return "ExemptElytra";
    }

    @Override
    public void setState(AeroPlayer player, ConfigManager config, FeatureState state) {
        switch (state) {
            case ENABLED -> player.setExemptElytra(true);
            case DISABLED -> player.setExemptElytra(false);
            default -> player.setExemptElytra(isEnabledInConfig(player, config));
        }
    }

    @Override
    public boolean isEnabled(AeroPlayer player) {
        return player.isExemptElytra();
    }

    @Override
    public boolean isEnabledInConfig(AeroPlayer player, ConfigManager config) {
        return config.getBooleanElse("exempt-elytra", false);
    }

}
