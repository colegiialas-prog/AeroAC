package dev.aeroac.checks.impl.vehicle;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.player.AeroPlayer;

@CheckData(name = "VehicleC", stableKey = "grim.vehicle.vehicle_control", description = "Moved a vehicle in a way that did not match predicted vehicle control")
public class VehicleC extends Check {
    public VehicleC(AeroPlayer player) {
        super(player);
    }
}
