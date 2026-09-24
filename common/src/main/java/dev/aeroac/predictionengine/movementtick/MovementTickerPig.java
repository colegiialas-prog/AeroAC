package dev.aeroac.predictionengine.movementtick;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.predictionengine.predictions.input.Input;
import dev.aeroac.utils.data.packetentity.PacketEntityRideable;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;

public class MovementTickerPig extends MovementTickerRideable {
    public MovementTickerPig(AeroPlayer player) {
        super(player);
        this.movementInput = Input.createInput(player, 0, 0, 1);
    }

    @Override
    public float getSteeringSpeed() { // Vanilla multiples by 0.225f
        PacketEntityRideable pig = (PacketEntityRideable) player.compensatedEntities.self.getRiding();
        return (float) pig.getAttributeValue(Attributes.MOVEMENT_SPEED) * 0.225f;
    }
}
