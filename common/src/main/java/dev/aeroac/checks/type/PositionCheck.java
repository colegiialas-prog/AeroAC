package dev.aeroac.checks.type;

import ac.grim.grimac.api.AbstractCheck;
import dev.aeroac.utils.anticheat.update.PositionUpdate;

public interface PositionCheck extends AbstractCheck {

    default void onPositionUpdate(final PositionUpdate positionUpdate) {
    }
}
