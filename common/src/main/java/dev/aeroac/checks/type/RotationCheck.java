package dev.aeroac.checks.type;

import ac.grim.grimac.api.AbstractCheck;
import dev.aeroac.utils.anticheat.update.RotationUpdate;

public interface RotationCheck extends AbstractCheck {

    default void process(final RotationUpdate rotationUpdate) {
    }
}
