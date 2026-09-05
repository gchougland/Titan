package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Flies one voxel in from a point around the titan as it appears.
 *
 * <p>Lives only for as long as the flight, and while it is present the part sync system leaves this voxel
 * alone. That is the whole reason the effect is a component rather than a mode on the titan: two systems
 * writing one transform in the same tick would fight, and gating on the presence of a component means the
 * hand-off needs no coordination and finishes per voxel rather than all at once.
 *
 * <p>Runtime-only. A titan caught mid-spawn by a restart comes back assembled.
 */
public final class TitanSpawnFxComponent implements Component<EntityStore> {

    @Nonnull
    public static ComponentType<EntityStore, TitanSpawnFxComponent> getComponentType() {
        return TitanRegistry.getSpawnFxComponentType();
    }

    @Nonnull
    private final Vector3d origin = new Vector3d();
    private float delay;
    private float duration = 1f;
    private float elapsed;

    /** For the component registry. */
    public TitanSpawnFxComponent() {
    }

    /**
     * @param origin   where the voxel starts, in world space
     * @param delay    seconds to hang there before setting off
     * @param duration seconds of flight
     */
    public TitanSpawnFxComponent(@Nonnull final Vector3d origin, final float delay, final float duration) {
        this.origin.set(origin);
        this.delay = delay;
        this.duration = Math.max(0.01f, duration);
    }

    @Nonnull
    public Vector3d getOrigin() {
        return origin;
    }

    /**
     * Advances the flight.
     *
     * @return how far along it is, {@code 0} while still waiting out the delay and {@code 1} on arrival
     */
    public float advance(final float dt) {
        elapsed += dt;
        if (elapsed <= delay) return 0f;
        return Math.min(1f, (elapsed - delay) / duration);
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanSpawnFxComponent();
        copy.origin.set(origin);
        copy.delay = delay;
        copy.duration = duration;
        copy.elapsed = elapsed;
        return copy;
    }
}
