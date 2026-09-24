package dev.aeroac.manager.player.features;

import dev.aeroac.manager.player.features.types.AeroFeature;
import dev.aeroac.utils.anticheat.LogUtil;
import com.google.common.collect.ImmutableMap;

import java.util.regex.Pattern;

public class FeatureBuilder {

    private static final Pattern VALID = Pattern.compile("[a-zA-Z0-9_]{1,64}");
    private final ImmutableMap.Builder<String, AeroFeature> mapBuilder = ImmutableMap.builder();

    public <T extends AeroFeature> void register(T feature) {
        if (!VALID.matcher(feature.getName()).matches()) {
            LogUtil.error("Invalid feature name: " + feature.getName());
            return;
        }
        mapBuilder.put(feature.getName(), feature);
    }

    public ImmutableMap<String, AeroFeature> buildMap() {
        return mapBuilder.build();
    }

}
