package dev.aeroac.checks.impl.aim.processor;

import dev.aeroac.checks.Check;
import dev.aeroac.checks.impl.aim.AuraSnapBack;
import dev.aeroac.checks.type.PacketCheck;
import dev.aeroac.player.AeroPlayer;
import dev.aeroac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

/**
 * Who the player last attacked, how many attacks and movement packets so far, for the aim checks that
 * judge rotations around hits. Rotation checks run while a movement packet is handled, before this sees
 * it, so {@link #flyings()} there counts the packets before the current one.
 */
public class CombatTargetTracker extends Check implements PacketCheck {
    private int targetId = Integer.MIN_VALUE;
    private int attacks;
    private int flyings;

    public CombatTargetTracker(AeroPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            flyings++;
        } else if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            onAttack(new WrapperPlayClientAttack(event).getEntityId());
        } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) onAttack(packet.getEntityId());
        }
    }

    private void onAttack(int entityId) {
        targetId = entityId;
        attacks++;
        // The hand-back is timed from the hit itself, not from the next rotation
        player.checkManager.getRotationCheck(AuraSnapBack.class).onAttack();
    }

    /** Attacks seen so far; a change between two rotations means one was sent between them. */
    public int attacks() {
        return attacks;
    }

    /** Movement packets seen so far. */
    public int flyings() {
        return flyings;
    }

    /** The last attacked entity, if Grim still tracks it. */
    public PacketEntity target() {
        return player.compensatedEntities.entityMap.get(targetId);
    }
}
