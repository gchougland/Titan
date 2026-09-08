package com.hexvane.titan.dunewyrm;

/**
 * Tunables for Dunewyrm combat and motion that are not already on {@link com.hexvane.titan.asset.TitanVariantAsset}.
 */
public final class DunewyrmTuning {

    public static final int BODY_COUNT = 8;
    public static final float SEGMENT_HEALTH = 400f;
    /**
     * Shorter snakes are weaker: speed and hit damage scale linearly with remaining body count, from 1.0
     * at full length down to these floors as the last segment goes. Splits therefore leave two snakes that
     * are each easier than the whole was.
     */
    public static final float SHORT_SPEED_FLOOR = 0.55f;
    public static final float SHORT_DAMAGE_FLOOR = 0.4f;

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
    public static final double SELF_AVOID_RADIUS = 12.0;
    /** Tightest arc the head may trace — tighter than this stacks segments on top of each other. */
    public static final double MIN_TURN_RADIUS = 8.0;
    /** How far ahead the head probes its own body when picking a heading. */
    public static final double AVOID_LOOKAHEAD = 18.0;
    /** Widen the orbit when the player is fighting from beside the body (stops accordion bunching). */
    public static final float ORBIT_FLANK_MULT = 1.45f;
    /** Player within this distance of a body counts as flanking. */
    public static final double FLANK_BODY_RANGE = 7.0;

    /**
     * Orbit distance while the attack cooldown runs — close enough to stay a threat. Once the cooldown is
     * up the snake leaves the ring and comes straight at the player ({@link #HUNT_SPEED_MULT}).
     */
    public static final float ORBIT_RADIUS = 22f;
    public static final float ORBIT_RADIUS_JITTER = 5f;
    public static final float ORBIT_SPEED = 0.4f;
    /** Speed boost while closing on the player for an attack. */
    public static final float HUNT_SPEED_MULT = 1.15f;
    /** Top speed while a player is standing on the snake — the old walking pace, which riders could hold. */
    public static final float RIDER_SPEED_CAP = 4.5f;
    /** Soft leash from spawn home so the snake cannot wander the whole desert. */
    public static final float HOME_LEASH = 48f;

    /** After a split, how long each snake flees away from the break point. */
    public static final float SPLIT_FLEE_DURATION = 2.2f;
    public static final float SPLIT_SEPARATION = 14f;
    /** Up/down thrash played right after a split before flee/slither. */
    public static final float FLAIL_DURATION = 1.4f;
    public static final float FLAIL_AMP = 3.8f;

    public static final float CHARGE_WINDUP = 1.05f;
    public static final float CHARGE_DURATION = 2.0f;
    public static final float CHARGE_SPEED_MULT = 3.4f;
    /** Authored charge beam length in blocks — telegraph is placed from the snout forward only. */
    public static final float CHARGE_TELEGRAPH_LENGTH = 16f;
    /** How far off the player the head may be (radians) and still commit to a charge / cobra. */
    public static final float CHARGE_FACING = 0.85f;
    public static final float COBRA_FACING = 1.2f;
    /** rad/s the head swings onto the (fixed) charge line during the windup. */
    public static final float CHARGE_AIM_RATE = 1.7f;
    /** Relative odds when an attack rolls: charge is common, poison occasional. */
    public static final float CHARGE_CHANCE = 0.9f;
    public static final float COBRA_CHANCE = 0.45f;
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
    /**
     * Bodies that leave the ground when it rears: head, then two lifted bodies, then the third settling back
     * to the sand. More than this and the whole front half hung in the air.
     */
    public static final int COBRA_CURVE_BODIES = 3;
    /** How far the lifted neck bows back (as a fraction of the rise) to read as an S rather than a ramp. */
    public static final float COBRA_NECK_ARCH = 0.3f;
    public static final float COBRA_COOLDOWN_EXTRA = 1.5f;
    /** rad/s the reared head tracks the player while telegraphing and spraying. */
    public static final float COBRA_TRACK_RATE = 1.3f;

    public static final float POISON_RADIUS = 8f;
    /** Where the cloud lands: this far ahead of the head on the ground. Ring, cloud and puffs all use it. */
    public static final double POISON_CENTRE_AHEAD = 9.0;
    /** Breath puffs are scattered out to this fraction of the hazard radius (the mist itself spreads further). */
    public static final float POISON_PUFF_SPREAD = 0.9f;
    /** Feet up to this far above the cloud's ground point are inside it (covers jumping and low ledges). */
    public static final double POISON_HEIGHT = 5.0;
    /** ...and this far below (a dip or trench inside the circle is not shelter). */
    public static final double POISON_DEPTH = 2.5;
    /** Radius of the jet from mouth to cloud that also poisons while the breath is out. */
    public static final double POISON_JET_RADIUS = 2.5;
    public static final float POISON_ATTACK_RANGE = 14f;
    public static final float POISON_LINGER = 7f;
    public static final float POISON_PARTICLE_SCALE = 2.4f;
    public static final float POISON_CONE_SCALE = 2.8f;
    public static final String POISON_EFFECT = "Poison";
    /** Vanilla combat poison — known-visible bubble burst. */
    public static final String POISON_PARTICLE = "Impact_Poison";
    public static final String POISON_CONE_PARTICLE = "Impact_Poison";
    /** Status-effect cloud for the linger puddle. */
    public static final String POISON_CLOUD_PARTICLE = "Effect_Poison";
    public static final String POISON_MIST_PARTICLE = "Dunewyrm_Poison_Breath";

    public static final float CONTACT_KNOCKBACK = 0.22f;
    /** Charging head: how far ahead of the head centre the ram probe sits, and its radius. */
    public static final double RAM_LEAD = 1.5;
    public static final double RAM_RADIUS = 3.4;
    /** Feet this far above the head centre count as riding the skull and are not rammed. */
    public static final double RAM_SPARE_HEIGHT = 3.0;
    /** Within this of the head centre the player is inside the skull and gets placed back outside it. */
    public static final double RAM_EJECT_RADIUS = 3.0;
    /** How far beside the head an ejected player is put down (clear of shell voxels and jaw). */
    public static final double RAM_EJECT_DISTANCE = 5.5;
    /** Sideways shove off the charge line when rammed, and the lift that goes with it. */
    public static final float RAM_KNOCKBACK = 0.95f;
    public static final float RAM_LIFT = 0.3f;
    public static final float MAX_TURN_RATE = 0.95f;
    /** Max blocks the head may climb in one move — pillars must be smashed or walked around. */
    public static final float MAX_CLIMB = 1.0f;
    /** How fast the head may drop toward the neighbourhood floor (getting off pillars). */
    public static final float MAX_DROP = 2.75f;
    /** Local column this far above the neighbourhood floor is treated as a pillar, not a ramp. */
    public static final float PILLAR_CLEARANCE = 2.0f;
    /** Neighbourhood radius (blocks) used to find the surrounding floor under pillars/structures. */
    public static final int FLOOR_SAMPLE_RADIUS = 2;
    /** How steeply the body may drape between neighbouring segments. */
    public static final float SEGMENT_GROUND_STEP = 3.0f;

    public static final float CONTACT_DAMAGE = 24f;
    public static final float CONTACT_INTERVAL = 0.45f;
    public static final double CONTACT_RADIUS = 3.2;

    public static final float TUNNEL_DURATION = 7f;
    public static final float TUNNEL_DEPTH = 4f;
    public static final float TUNNEL_SPEED_MULT = 1.6f;
    /** How fast tunnelDepth returns to 0 when the snake climbs back out (blocks/sec). */
    public static final float TUNNEL_EMERGE_RATE = 3.2f;
    public static final int SCORPIONS_PER_TUNNEL = 4;
    public static final float TUNNEL_THRESHOLD_HIGH = 0.66f;
    public static final float TUNNEL_THRESHOLD_LOW = 0.33f;

    public static final float TONGUE_INTERVAL_MIN = 2.5f;
    public static final float TONGUE_INTERVAL_MAX = 5.5f;
    public static final float TONGUE_DURATION = 0.35f;
    public static final float TONGUE_EXTEND = 2.4f;
    public static final float TONGUE_MOUTH = 2.8f;
    /** Prefab faces the wrong way relative to head forward — flip so the fork leads. */
    public static final float TONGUE_YAW_OFFSET = (float) Math.PI;

    public static final float JAW_OPEN_ANGLE = 0.45f;

    public static final float ATTACK_COOLDOWN = 5.4f;
    public static final float SLITHER_SINE_AMP = 3.5f;
    public static final float SLITHER_SINE_FREQ = 0.85f;
    public static final float SLITHER_DUST_INTERVAL = 0.28f;
    public static final float SLITHER_DUST_SCALE = 2.4f;
    /** Vanilla sand impact — reliably visible; custom dig FX layered on top. */
    public static final String SLITHER_PARTICLE = "Block_Land_Hard_Sand";

    public static final float SMASH_COOLDOWN = 0.2f;
    public static final int SMASH_RADIUS = 2;
    public static final int SMASH_HEIGHT = 5;
    /** Never smash below the feet — that was carving trenches the snake then fell into. */
    public static final int SMASH_DEPTH = 0;

    public static final int PATH_CAPACITY = 512;

    public static final String PREFAB_HEAD = "Titan/Dunewyrm/Dunewyrm_Head";
    public static final String PREFAB_JAW = "Titan/Dunewyrm/Dunewyrm_Jaw";
    public static final String PREFAB_TONGUE = "Titan/Dunewyrm/Dunewyrm_Tongue";
    public static final String PREFAB_SEGMENT1 = "Titan/Dunewyrm/Dunewyrm_Segment1";
    public static final String PREFAB_SEGMENT2 = "Titan/Dunewyrm/Dunewyrm_Segment2";
    public static final String PREFAB_TAIL = "Titan/Dunewyrm/Dunewyrm_Tail";

    public static final String COLLIDER_CONFIG = "Titan_Platform";

    /**
     * Segments further than this from every player only pose every Nth tick (the whole segment together,
     * so it reads as a lower frame rate at range rather than jitter). Anything closer poses every tick.
     */
    public static final double PART_SYNC_MID_DISTANCE = 40.0;
    public static final int PART_SYNC_MID_STRIDE = 2;
    public static final double PART_SYNC_FAR_DISTANCE = 80.0;
    public static final int PART_SYNC_FAR_STRIDE = 4;
    /** Re-sample the drape ground under a body segment only after it has slid this far (blocks). */
    public static final double DRAPE_RESAMPLE_DISTANCE = 0.75;
    /** Blocks per second a resting segment may rise or sink toward the terrain under it. */
    public static final double DRAPE_EASE_RATE = 3.0;
    /** A ground change bigger than this is a relocation, not a bump: snap instead of easing. */
    public static final double DRAPE_EASE_SNAP = 8.0;
    /** Body pitch on the ground: ignore this much joint-to-joint tilt (radians)... */
    public static final float GROUND_PITCH_DEADZONE = 0.03f;
    /** ...scale what is left by this... */
    public static final float GROUND_PITCH_SCALE = 0.5f;
    /** ...and never tilt a resting segment more than this (radians, ~14°). */
    public static final float GROUND_PITCH_MAX = 0.25f;

    /**
     * How much of a prefab's AABB is filled by the invisible solid core. Less than 1 so the outer
     * climbable voxels still stick out as the walkable skin.
     */
    public static final float SOLID_FILL_INSET = 0.88f;
    /** Feet this far above a segment's ground line count as riding it rather than standing beside it. */
    public static final double RIDER_MIN_HEIGHT = 0.75;
    /** Half-width of a body segment's flank zone: inside it a brush shoves straight out the side. */
    public static final double BODY_EJECT_HALF_WIDTH = 2.6;
    /** Closer to the axis than this at ground level means wedged inside the body: teleport out first. */
    public static final double BODY_EJECT_TRAP_WIDTH = 1.9;
    public static final double BODY_EJECT_DISTANCE = 4.5;
    /** Head core is nearly the full skull so a charge shoves players rather than swallowing them. */
    public static final float HEAD_SOLID_FILL_INSET = 0.94f;

    public static final String DIG_PARTICLE = "Dunewyrm_Digging";
    public static final float DIG_PARTICLE_SCALE = 2.6f;
    public static final float DIG_PARTICLE_HEIGHT = 0.75f;
    /** How far a player may be from a site before the snake is reaped for a clean respawn. */
    public static final float SITE_KEEP_RADIUS = 96f;
    /** How often uncleared sites check for a missing / broken snake. */
    public static final float SITE_MAINTAIN_INTERVAL = 2.5f;
    public static final String BREAK_PARTICLE = "Block_Land_Hard_Sand";
    public static final String CHARGE_SOUND = "SFX_Snake_Alerted";
    public static final String COBRA_SOUND = "SFX_Snake_Alerted";
    public static final String DIG_SOUND = "SFX_Golem_Earth_Alerted";
    public static final String BREAK_SOUND = "SFX_Stone_Break";
    public static final String TONGUE_SOUND = "SFX_Snake_Idle";

    private DunewyrmTuning() {
    }
}
