package com.hexvane.titan.ledge;

/**
 * Tunables for the ledge-cling prototype. Kept in one place so playtesting is a single edit.
 */
public final class TitanLedge {

    /** Half-length of the hang rail on each nub, in blocks. */
    public static final float HALF_WIDTH = 0.55f;

    /** How fast A/D slides along the rail, in blocks per second. */
    public static final float STRAFE_SPEED = 2.5f;

    /** How far below the nub the hang point sits, in blocks. */
    public static final float HANG_DOWN = 1.2f;

    /** How far out from the nub (along its forward) the hang point sits, in blocks. */
    public static final float HANG_OUT = 0.35f;

    /** How far above the nub a pull-up places the player, in blocks. */
    public static final float PULL_UP = 1.6f;

    /** How far forward of the nub a pull-up places the player, in blocks. */
    public static final float PULL_FORWARD = 0.8f;

    /** Jump-off launch speed, in blocks per second. */
    public static final float JUMP_SPEED = 12f;

    /** Max gap between rail ends for a seamless neighbor handoff, in blocks. */
    public static final float TRANSFER_GAP = 1.25f;

    /** Ignore attachment updates smaller than this, in blocks. */
    public static final float OFFSET_EPSILON = 0.02f;

    /** Block type rendered as each grab ledge (horizontal half-slab). */
    public static final String BLOCK_ID = "Soil_Clay_Smooth_Yellow2_Half";

    /** Root interaction asset that opens the UseEntity door for ledges. */
    public static final String USE_INTERACTION = "Titan_Ledge_Use";

    /** How far ahead of the player a single ledge spawns, in blocks. */
    public static final double SPAWN_AHEAD = 2.0;

    /** Search radius when looking for a neighbor ledge, in blocks. */
    public static final double NEIGHBOR_SEARCH = 4.0;

    /** Max vertical difference allowed when transferring, in blocks. */
    public static final double NEIGHBOR_Y_TOLERANCE = 0.75;

    /** Min absolute rail-axis alignment (dot of rights) to accept a neighbor. */
    public static final double NEIGHBOR_ALIGN = 0.85;

    private TitanLedge() {
    }
}
