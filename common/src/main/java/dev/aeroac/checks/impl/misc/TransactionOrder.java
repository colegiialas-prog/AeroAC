package dev.aeroac.checks.impl.misc;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.CheckData;
import dev.aeroac.player.AeroPlayer;

@CheckData(name = "TransactionOrder", stableKey = "grim.ping.invalid_transaction_order", description = "Sent transaction or ping responses in an invalid order")
public class TransactionOrder extends Check {
    public TransactionOrder(AeroPlayer player) {
        super(player);
    }
}
