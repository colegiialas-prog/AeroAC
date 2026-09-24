package dev.aeroac.checks.impl.sprint;

import ac.grim.grimac.api.storage.verbose.Verbose;
import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.PostPredictionCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

@CheckData(name = "SprintA", stableKey = "grim.sprint.hunger", description = "Sprinting with too low hunger", setback = 0)
public class SprintA extends Check implements PostPredictionCheck {
    private static final Verbose V = Verbose.of("hunger={uint}");

    public SprintA(AeroPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) return;

        // Players can sprint if they're able to fly
        // Players can also sprint if they are on a camel, regardless of their hunger level
        if (player.canFly || EntityTypes.isTypeInstanceOf(player.getVehicleType(), EntityTypes.CAMEL)) return;

        if (player.food <= 6.0F) {
            if (player.isSprinting) {
                flagWithSetback(V.write(verbose()).uint(player.food));
            } else {
                reward();
            }
        }
    }
}
