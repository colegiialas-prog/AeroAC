package dev.aeroac.utils.data.packetentity;

import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.data.VectorData;

import java.util.Set;

public interface JumpableEntity {

    boolean isJumping();

    void setJumping(boolean jumping);

    float getJumpPower();

    void setJumpPower(float jumpPower);

    boolean canPlayerJump(AeroPlayer player);

    boolean hasSaddle();

    void executeJump(AeroPlayer player, Set<VectorData> possibleVectors);

}
