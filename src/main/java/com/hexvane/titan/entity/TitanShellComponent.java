package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * The single health pool of a titan whose own shell is the thing being hit.
 *
 * <p>An ore node is its own target: it carries its own health, and breaking one takes a piece off the titan
 * where it stood. A shell is the opposite. Every voxel of it feeds one pool, and none of them can be taken
 * off individually — an egg with a hole knocked in one side would show what is curled up inside it, which
 * is the surprise the whole stage exists for. So the voxels are hit and the pool is what drains, and the
 * shell stays whole until it comes apart all at once.
 *
 * <p>{@link com.hexvane.titan.system.TitanShellDamageSystem} does the draining. What breaking the shell
 * then means is not decided here: for the one shell there is, {@link com.hexvane.titan.yaga.YagaEggSystem}
 * reads {@link #isBroken()} and hatches.
 *
 * <p>Runtime-only, like the rest of a titan. An egg comes back whole after a restart.
 */
public final class TitanShellComponent implements Component<EntityStore> {

    @Nonnull
    public static ComponentType<EntityStore, TitanShellComponent> getComponentType() {
        return TitanRegistry.getShellComponentType();
    }

    private float health;
    private boolean broken;

    /** For the component registry. */
    public TitanShellComponent() {
    }

    /** @param health total damage the whole shell absorbs, however many voxels that is spread over */
    public TitanShellComponent(final float health) {
        this.health = health;
    }

    /**
     * Takes a hit out of the shell.
     *
     * <p>Locked because the voxels are hit on whichever thread the damage arrived on, and a shell is a few
     * dozen blocks that a player with a wide swing can catch several of at once.
     *
     * @return whether this was the hit that broke it, true exactly once
     */
    public synchronized boolean absorb(final float amount) {
        if (broken) return false;

        health -= amount;
        if (health > 0f) return false;

        // Latched rather than left implied by the health, so a variant that forgot to give its shell any
        // health stands there to be hit instead of hatching the moment it spawns.
        broken = true;
        return true;
    }

    public synchronized boolean isBroken() {
        return broken;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanShellComponent();
        copy.health = health;
        copy.broken = broken;
        return copy;
    }
}
