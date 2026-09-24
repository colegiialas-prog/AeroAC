package dev.aeroac.utils.data;

import dev.aeroac.utils.math.Vector3dm;

public class HitData {
    Vector3dm blockHitLocation;

    public HitData(Vector3dm blockHitLocation) {
        this.blockHitLocation = blockHitLocation;
    }

    public dev.aeroac.utils.math.Vector3dm getBlockHitLocation() {
        return this.blockHitLocation;
    }
}
