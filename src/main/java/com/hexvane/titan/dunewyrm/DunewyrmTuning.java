package com.hexvane.titan.dunewyrm;

/**
 * Tunables for Dunewyrm combat and motion that are not already on {@link com.hexvane.titan.asset.TitanVariantAsset}.
 */
public final class DunewyrmTuning {

    public static final int BODY_COUNT = 8;
    public static final float SEGMENT_HEALTH = 400f;

    /**
     * Arc spacing between consecutive body centres. Segment prefabs are ~18 long, so this overlaps them
     * by a few blocks instead of leaving gaps.
     */
    public static final double SEGMENT_SPACING = 12.0;
    /** Path distance from head to first body centre — short so the head nests into the neck. */
    public static final double HEAD_BODY_SPACING = 6.0;
    /** Tiny shrink so the head voxels don't clip the first body segment. */
    public static final float HEAD_SCALE = 0.96f;
    /** Tail nests into the last body. */
    public static final double TAIL_SPACING = 5.5;
    /** How much the resting coil bends (radians across the full chain). */
    public static final float SPAWN_CURL = 2.1f;

    /** How close the head may approach a trailing body segment before it steers away. */
    public static final double SELF_AVOID_RADIUS = 10.0;

    /** Preferred orbit distance while slithering — wide passes rather than tight loops. */
    public static final float ORBIT_RADIUS = 28f;
    public static final float ORBIT_RADIUS_JITTER = 6f;
    public static final float ORBIT_SPEED = 0.4f;

    /** After a split, how long each snake flees away from the break point. */
    public static final float SPLIT_FLEE_DURATION = 2.2f;
    public static final float SPLIT_SEPARATION = 14f;

    public static final float CHARGE_WINDUP = 1.05f;
    public static final float CHARGE_DURATION = 2.2f;
    public static final float CHARGE_SPEED_MULT = 2.2f;
    /** Authored charge beam length in blocks — telegraph is placed from the snout forward only. */
    public static final float CHARGE_TELEGRAPH_LENGTH = 16f;
    /** Relative odds when an attack rolls: charge is the default, poison is rare. */
    public static final float CHARGE_CHANCE = 0.88f;
    public static final float COBRA_CHANCE = 0.04f;
    /** Poison only while the snake still has enough body left to rear into a cobra. */
    public static final int MIN_BODIES_FOR_POISON = 4;

    public static final float COBRA_DURATION = 4.2f;
    public static final float COBRA_RISE = 12f;
    /** Head pulls back during windup to form the S, then leans forward to spray. */
    public static final float COBRA_RECOIL = 5.5f;
    public static final float COBRA_LEAN = 4.0f;
    public static final float COBRA_WINDUP = 1.15f;
    public static final float COBRA_SPRAY_START = 1.25f;
    public static final float COBRA_SPRAY_END = 2.8f;
    /** How many body segments participate in the rearing curve. */
    public static final int COBRA_CURVE_BODIES = 5;
    /** Extra cooldown applied when a cobra starts, on top of {@link #ATTACK_COOLDOWN}. */
    public static final float COBRA_COOLDOWN_EXTRA = 4.0f;

    public static final float POISON_RADIUS = 8f;
    /** Player must be this close (horizontal) before poison is allowed. */
    public static final float POISON_ATTACK_RANGE = 10f;
    public static final float POISON_LINGER = 7f;
    public static final float POISON_PARTICLE_SCALE = 1.35f;
    public static final float POISON_CONE_SCALE = 1.5f;
    public static final String POISON_EFFECT = "Poison";
    public static final String POISON_PARTICLE = "Dunewyrm_Poison_Breath";
    public static final String POISON_CONE_PARTICLE = "Dunewyrm_Poison_Cone";

    /** Contact shove — soft nudge so standing on the snake stays playable. */
    public static final float CONTACT_KNOCKBACK = 0.28f;

    /** Radians per second — wide arcs only; forbids whip-around 180s. */
    public static final float MAX_TURN_RATE = 0.65f;
    /** Soft cap on how far a single steer (avoidance included) may pull facing this step. */
    public static final float MAX_TURN_STEP = 0.2f;
    /** Max blocks the head may climb in one move sample — keeps pillars safe. */
    public static final float MAX_CLIMB = 3.0f;
    public static final float MAX_DROP = 6.0f;

    public static final float CONTACT_DAMAGE = 8f;
    public static final float CONTACT_INTERVAL = 0.45f;
    public static final double CONTACT_RADIUS = 3.2;

    public static final float TUNNEL_DURATION = 7f;
    public static final float TUNNEL_DEPTH = 4f;
    public static final float TUNNEL_SPEED_MULT = 1.6f;
    public static final int SCORPIONS_PER_TUNNEL = 4;
    public static final float TUNNEL_THRESHOLD_HIGH = 0.66f;
    public static final float TUNNEL_THRESHOLD_LOW = 0.33f;

    public static final float TONGUE_INTERVAL_MIN = 2.5f;
    public static final float TONGUE_INTERVAL_MAX = 5.5f;
    public static final float TONGUE_DURATION = 0.35f;
    /** How far past the mouth the tongue extends when flicking. */
    public static final float TONGUE_EXTEND = 2.4f;
    /** Base mouth offset ahead of the head pivot when the tongue is retracted. */
    public static final float TONGUE_MOUTH = 2.8f;

    public static final float JAW_OPEN_ANGLE = 0.45f;

    public static final float ATTACK_COOLDOWN = 5.5f;
    public static final float SLITHER_SINE_AMP = 3.5f;
    public static final float SLITHER_SINE_FREQ = 0.85f;

    public static final int PATH_CAPACITY = 512;

    public static final String PREFAB_HEAD = "Titan/Dunewyrm/Dunewyrm_Head";
    public static final String PREFAB_JAW = "Titan/Dunewyrm/Dunewyrm_Jaw";
    public static final String PREFAB_TONGUE = "Titan/Dunewyrm/Dunewyrm_Tongue";
    public static final String PREFAB_SEGMENT1 = "Titan/Dunewyrm/Dunewyrm_Segment1";
    public static final String PREFAB_SEGMENT2 = "Titan/Dunewyrm/Dunewyrm_Segment2";
    public static final String PREFAB_TAIL = "Titan/Dunewyrm/Dunewyrm_Tail";

    public static final String COLLIDER_CONFIG = "Titan_Platform";

    public static final String DIG_PARTICLE = "Dunewyrm_Digging";
    /** Packet scale for dig bursts — Undead_Digging was tiny on a giant snake. */
    public static final float DIG_PARTICLE_SCALE = 2.6f;
    /** How far above the buried head the dig FX sits (not full surface height). */
    public static final float DIG_PARTICLE_HEIGHT = 0.75f;
    public static final String BREAK_PARTICLE = "Block_Land_Hard_Sand";
    public static final String CHARGE_SOUND = "SFX_Snake_Alerted";
    public static final String COBRA_SOUND = "SFX_Snake_Alerted";
    public static final String DIG_SOUND = "SFX_Golem_Earth_Alerted";
    public static final String BREAK_SOUND = "SFX_Stone_Break";
    public static final String TONGUE_SOUND = "SFX_Snake_Idle";

    private DunewyrmTuning() {
    }
}
