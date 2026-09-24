package dev.aeroac.utils.data;

import dev.aeroac.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.protocol.world.BlockFace;

import java.util.List;

public record PistonTemplate(BlockFace dir,
                      List<SimpleCollisionBox> boxes,
                      boolean push, boolean slime, boolean honey) {
}
