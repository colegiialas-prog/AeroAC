package dev.aeroac.checks.type;

import ac.grim.grimac.api.AbstractCheck;
import dev.aeroac.utils.anticheat.update.VehiclePositionUpdate;

public interface VehicleCheck extends AbstractCheck {

    void process(final VehiclePositionUpdate vehicleUpdate);
}
