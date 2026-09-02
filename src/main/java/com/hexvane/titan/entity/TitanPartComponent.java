package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

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

    /**
     * The transform the clients were last given, and whether they have been given one at all.
     *
     * @see #hasDriftedFrom
     */
    @Nonnull
    private final Vector3d sentPosition = new Vector3d();
    @Nonnull
    private final Rotation3f sentRotation = new Rotation3f();
    private boolean everSent;

    /**
     * Where in the sync interval this part's turn falls, as a fraction of it.
     *
     * <p>Rolled per part rather than shared so that slowing the sync rate spreads the body's updates over
     * the interval instead of resending all of it on one tick out of every few. The peak is the problem, not
     * the average: one enormous packet is what drops updates, so a saving that leaves the peak alone is not
     * a saving at all.
     */
    private final float syncPhase = ThreadLocalRandom.current().nextFloat();
    /** Negative until the interval is known, which is only at tick time because it is a config value. */
    private float syncTimer = -1f;

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
     * <p>The rotation is deliberately slow now. The engine restates the scale to any viewer that has newly
     * become able to see the entity, which covers a client arriving after the mark was consumed, so this is
     * insurance against an unidentified cause rather than the mechanism that keeps a titan the right size.
     *
     * @return {@code true} when this part is due
     */
    public boolean consumeScaleRefresh(final float dt, final float interval) {
        scaleRefreshTimer -= dt;
        if (scaleRefreshTimer > 0f) return false;
        scaleRefreshTimer = interval;
        return true;
    }

    /**
     * Whether this part has moved far enough from what the clients were last told to be worth telling them
     * again, and records the new transform as sent if so.
     *
     * <p>The saving is possible because the engine's own check for whether a transform needs replicating is
     * exact equality, so leaving the transform alone costs nothing at all — no packet, no dirty flag. What
     * this buys is the difference between every voxel of a walking titan reporting in every tick and only
     * the ones that have visibly gone somewhere.
     *
     * <p>Measuring against what was last sent rather than against last tick's position is what keeps the
     * error bounded. A part is never further from the truth than the tolerance, however long the titan
     * walks, where comparing successive positions would let a part fall quietly further behind every tick.
     *
     * <p>Angles are compared component-wise, which reads wrong for the wrap at half a turn and is safe in
     * the direction that matters: the wrap makes two close angles look far apart, so the worst it does is
     * send an update that was not needed.
     *
     * @param positionEpsilon blocks of drift to tolerate; zero sends whenever anything changed at all
     * @param rotationEpsilon radians of drift to tolerate on the block's own orientation
     */
    public boolean hasDriftedFrom(@Nonnull final Vector3d position,
                                  @Nonnull final Rotation3f rotation,
                                  final double positionEpsilon,
                                  final double rotationEpsilon) {

        if (everSent
            && position.distanceSquared(sentPosition) <= positionEpsilon * positionEpsilon
            && Math.abs(rotation.pitch() - sentRotation.pitch()) <= rotationEpsilon
            && Math.abs(rotation.yaw() - sentRotation.yaw()) <= rotationEpsilon
            && Math.abs(rotation.roll() - sentRotation.roll()) <= rotationEpsilon) {
            return false;
        }

        everSent = true;
        sentPosition.set(position);
        sentRotation.set(rotation);
        return true;
    }

    /**
     * Counts down to this part's next slot in the sync interval.
     *
     * <p>Unlike {@link #hasDriftedFrom}, which only ever withholds an update that would have said nothing,
     * this withholds updates that would have said something, so it costs real smoothness in exchange for
     * bandwidth. Off unless the interval is configured.
     *
     * @param interval seconds between one part's updates; zero or less means every tick
     * @return {@code true} when this part's turn has come round
     */
    public boolean consumeSyncSlot(final float dt, final double interval) {
        if (interval <= 0) {
            syncTimer = -1f;
            return true;
        }
        if (syncTimer < 0f) syncTimer = (float) (syncPhase * interval);

        syncTimer -= dt;
        if (syncTimer > 0f) return false;
        syncTimer = (float) interval;
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
        copy.sentPosition.set(sentPosition);
        copy.sentRotation.set(sentRotation);
        copy.everSent = everSent;
        copy.syncTimer = syncTimer;
        copy.detached = detached;
        copy.velocity.set(velocity);
        copy.angularVelocity.set(angularVelocity);
        copy.resting = resting;
        copy.lifetime = lifetime;
        copy.despawnAfter = despawnAfter;
        return copy;
    }
}
