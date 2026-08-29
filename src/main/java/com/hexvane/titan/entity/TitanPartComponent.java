package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marks one voxel of a titan. The part follows its bone every tick until the titan dies, at which point it
 * detaches and tumbles under its own integration.
 *
 * <p>Runtime-only: parts are rebuilt from the skeleton whenever a titan is spawned, so there is no codec
 * and nothing is written to disk.
 */
public final class TitanPartComponent implements Component<EntityStore> {

    public static ComponentType<EntityStore, TitanPartComponent> getComponentType() {
        return TitanRegistry.getPartComponentType();
    }

    @Nullable
    private Ref<EntityStore> owner;
    private int boneIndex;
    /** Offset from the bone pivot, in model units. */
    @Nonnull
    private final Vector3d localOffset = new Vector3d();

    private boolean detached;
    @Nonnull
    private final Vector3d velocity = new Vector3d();
    @Nonnull
    private final Vector3d angularVelocity = new Vector3d();
    private boolean resting;
    private float lifetime;

    public TitanPartComponent() {
    }

    public TitanPartComponent(@Nonnull final Ref<EntityStore> owner, final int boneIndex, @Nonnull final Vector3d localOffset) {
        this.owner = owner;
        this.boneIndex = boneIndex;
        this.localOffset.set(localOffset);
    }

    @Nullable
    public Ref<EntityStore> getOwner() {
        return owner;
    }

    public int getBoneIndex() {
        return boneIndex;
    }

    @Nonnull
    public Vector3d getLocalOffset() {
        return localOffset;
    }

    public boolean isDetached() {
        return detached;
    }

    public void detach(@Nonnull final Vector3d initialVelocity, @Nonnull final Vector3d spin) {
        this.detached = true;
        this.velocity.set(initialVelocity);
        this.angularVelocity.set(spin);
    }

    @Nonnull
    public Vector3d getVelocity() {
        return velocity;
    }

    @Nonnull
    public Vector3d getAngularVelocity() {
        return angularVelocity;
    }

    /** Set once a detached part has come to a stop on the ground. */
    public boolean isResting() {
        return resting;
    }

    public void setResting(final boolean resting) {
        this.resting = resting;
    }

    /** Seconds since detaching, used to fade the debris out. */
    public float getLifetime() {
        return lifetime;
    }

    public void addLifetime(final float dt) {
        lifetime += dt;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanPartComponent();
        copy.owner = owner;
        copy.boneIndex = boneIndex;
        copy.localOffset.set(localOffset);
        copy.detached = detached;
        copy.velocity.set(velocity);
        copy.angularVelocity.set(angularVelocity);
        copy.resting = resting;
        copy.lifetime = lifetime;
        return copy;
    }
}
