package dev.aeroac.utils.data;

import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public final class BlockPrediction {
    private List<Vector3i> forBlockUpdate;
    private Vector3i blockPosition;
    private int originalBlockId;
    private final Vector3d playerPosition;
    /**
     * Where the player was before their movement first relied on this prediction - stood on it, pushed
     * against it, swam in it or walked through it - or null while it has not mattered.
     */
    private Vector3d positionBeforeUse;

    public BlockPrediction(List<Vector3i> forBlockUpdate, Vector3i blockPosition, int originalBlockId, Vector3d playerPosition) {
        this.forBlockUpdate = forBlockUpdate;
        this.blockPosition = blockPosition;
        this.originalBlockId = originalBlockId;
        this.playerPosition = playerPosition;
    }
}
