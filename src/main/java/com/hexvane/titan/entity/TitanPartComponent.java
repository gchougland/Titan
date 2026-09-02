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

    /**
     * The block's own orientation as a {@code RotationTuple} index, folded back in every time the bone pose
     * rewrites this part's transform. Without it a swinging leg flattens every slab and stair on it.
     */
    private int blockRotation;

    /** The size this voxel is meant to render at, kept so a drifted or undelivered value can be restored. */
    private float scale = 1f;
    private float scaleRefreshTimer;

    private boolean detached;
    @Nonnull
    private final Vector3d velocity = new Vector3d();
    @Nonnull
    private final Vector3d angularVelocity = new Vector3d();
    private boolean resting;
    private float lifetime;
    private float despawnAfter;

    public TitanPartComponent() {
    }

    public TitanPartComponent(@Nonnull final Ref<EntityStore> owner,
                              final int boneIndex,
                              @Nonnull final Vector3d localOffset,
                              final int blockRotation,
                              final float scale,
                              final float scaleRefreshPhase) {
        this.owner = owner;
        this.boneIndex = boneIndex;
        this.localOffset.set(localOffset);
        this.blockRotation = blockRotation;
        this.scale = scale;
        this.scaleRefreshTimer = scaleRefreshPhase;
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

    /** @see #blockRotation */
    public int getBlockRotation() {
        return blockRotation;
    }

    public float getScale() {
        return scale;
    }

    /**
     * Counts down to the next time this voxel should restate its size to the clients watching it.
     *
     * <p>The engine sends a block entity's scale once, when the value is marked out of date, and clears
     * that mark whether or not the packet reached anybody. Miss the window and the voxel renders at the
     * client's default size forever, which is where the occasional half-size titan full of gaps came from.
     * Restating it on a slow rotation repairs that, and also puts back a value that something else changed.
     * The phase is per part so the whole body does not resend on the same tick.
     *
     * @return {@code true} when this part is due
     */
    public boolean consumeScaleRefresh(final float dt, final float interval) {
        scaleRefreshTimer -= dt;
        if (scaleRefreshTimer > 0f) return false;
        scaleRefreshTimer = interval;
        return true;
    }

    public boolean isDetached() {
        return detached;
    }

    public void detach(@Nonnull final Vector3d initialVelocity, @Nonnull final Vector3d spin, final float despawnAfter) {
        this.detached = true;
        this.velocity.set(initialVelocity);
        this.angularVelocity.set(spin);
        this.despawnAfter = despawnAfter;
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

    /** Seconds this piece of rubble lasts before it is cleaned up. Rolled per part when it comes loose. */
    public float getDespawnAfter() {
        return despawnAfter;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanPartComponent();
        copy.owner = owner;
        copy.boneIndex = boneIndex;
        copy.localOffset.set(localOffset);
        copy.blockRotation = blockRotation;
        copy.scale = scale;
        copy.scaleRefreshTimer = scaleRefreshTimer;
        copy.detached = detached;
        copy.velocity.set(velocity);
        copy.angularVelocity.set(angularVelocity);
        copy.resting = resting;
        copy.lifetime = lifetime;
        copy.despawnAfter = despawnAfter;
        return copy;
    }
}
