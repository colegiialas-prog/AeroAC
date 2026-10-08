package dev.aeroac.neural.risk;

/**
 * Sources the engine combines. Grim families stay separate from model heads on purpose: an operator
 * must be able to see that a decision rested on deterministic geometry, on the model, or on both.
 */
public enum EvidenceType {
    AI_AIM(true),
    AI_KILLAURA(true),
    AI_TRIGGER(true),
    AI_OVERALL(true),
    /** Sustained low probability. Carries negative strength and can only reduce risk. */
    AI_RELIEF(true),
    GRIM_REACH(false),
    GRIM_WALL_HIT(false),
    GRIM_ENTITY_PIERCE(false),
    GRIM_PACKET_ORDER(false),
    /** Kept ticking without reporting a position, the way air-stuck clients hang in the air to hit. */
    GRIM_AIR_STUCK(false),
    /** Rotations only an aura produces: snapping back after hits, shaking, locking onto the centre. */
    GRIM_AURA_ROTATION(false),
    SESSION_ANOMALY(false);

    private final boolean model;

    EvidenceType(boolean model) { this.model = model; }

    public boolean fromModel() { return model; }

    /** Grim check names are not labels; they enter the engine as one evidence family among several. */
    public static EvidenceType forCheck(String checkName) {
        if (checkName == null) return null;
        if (checkName.equals("Reach")) return GRIM_REACH;
        if (checkName.equals("WallHit")) return GRIM_WALL_HIT;
        if (checkName.equals("EntityPierce")) return GRIM_ENTITY_PIERCE;
        if (checkName.startsWith("PacketOrder")) return GRIM_PACKET_ORDER;
        if (checkName.equals("AirStuck")) return GRIM_AIR_STUCK;
        if (checkName.startsWith("Aura")) return GRIM_AURA_ROTATION;
        return null;
    }
}
