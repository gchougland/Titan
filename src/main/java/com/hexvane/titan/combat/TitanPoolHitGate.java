package com.hexvane.titan.combat;

/**
 * Gates shared HP pools so one swing that overlaps many voxels only drains the pool once.
 *
 * <p>Dunewyrm segments and titan shells cancel per-voxel damage into one pool; without this an AOE melee
 * hit charges the pool once per overlapping block.
 */
public final class TitanPoolHitGate {

    private int lastAttacker = Integer.MIN_VALUE;
    private long lastTick = Long.MIN_VALUE;

    /**
     * @param attackerIndex entity index of the attacker, or a negative sentinel when there is none
     * @param tick          world tick the hit arrived on
     * @return {@code true} if this hit should drain the pool
     */
    public synchronized boolean accept(final int attackerIndex, final long tick) {
        if (tick == lastTick && attackerIndex == lastAttacker) {
            return false;
        }
        lastAttacker = attackerIndex;
        lastTick = tick;
        return true;
    }
}
