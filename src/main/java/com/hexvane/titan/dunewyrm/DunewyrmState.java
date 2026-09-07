package com.hexvane.titan.dunewyrm;

/**
 * Combat / motion states for a Dunewyrm snake instance.
 */
public enum DunewyrmState {
    /** Looking for a player within wake range. */
    IDLE,
    /** Slithering toward / around the target. */
    SLITHER,
    /** Committed charge in a fixed yaw. */
    CHARGE,
    /** Raised cobra pose releasing poison gas. */
    COBRA,
    /** Underground dig with surface particles and scorpion spawns. */
    TUNNEL,
    /** Despawning after its last body segment died. */
    DYING
}
