package dev.aeroac.checks.impl.movement;

import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.checks.type.PostPredictionCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

@CheckData(name = "NoSlow", stableKey = "grim.movement.noslow", description = "Was not slowed while using an item", setback = 5)
public class NoSlow extends Check implements PostPredictionCheck {
    // The player sends that they switched items the next tick if they switch from an item that can be used
    // to another item that can be used.  What the fuck mojang.  Affects 1.8 (and most likely 1.7) clients.
    public boolean didSlotChangeLastTick = false;
    private double offsetToFlag;
    private double bestOffset = 1;
    // Two unslowed ticks in a row, or unslowed ticks spread out densely enough; see UnslowedTickBuffer.
    private UnslowedTickBuffer unslowed = new UnslowedTickBuffer(2.0, 0.2);

    public NoSlow(AeroPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) return;

        // If the player was using an item for certain, and their predicted velocity had a flipped item
        if (player.packetStateData.isSlowedByUsingItem()) {
            // 1.8 users are not slowed the first tick they use an item, strangely
            boolean excused = false;
            if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8) && didSlotChangeLastTick) {
                didSlotChangeLastTick = false;
                excused = true;
            }

            boolean notSlowed = bestOffset > offsetToFlag;
            if (unslowed.usingItem(notSlowed, excused)) {
                flagWithSetback();
            } else if (!notSlowed) {
                reward();
            }
        } else {
            unslowed.notUsingItem();
        }
        bestOffset = 1;
    }

    public void handlePredictionAnalysis(double offset) {
        bestOffset = Math.min(bestOffset, offset);
    }

    @Override
    public void onReload(ConfigManager config) {
        offsetToFlag = config.getDoubleElse(getConfigName() + ".threshold", 0.001);
        // With the defaults any pattern that skips the slowdown more often than one tick in six
        // accumulates to a flag; a single desynced tick at the start or end of a use never does.
        unslowed = new UnslowedTickBuffer(Math.max(1.0, config.getDoubleElse(getConfigName() + ".buffer", 2.0)),
                Math.max(0.0, config.getDoubleElse(getConfigName() + ".buffer-decay", 0.2)));
    }
}
