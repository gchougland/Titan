package com.hexvane.titan.dunewyrm;

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
 * One voxel of a Dunewyrm segment. Posed each tick from the owning snake's segment transforms.
 */
public final class DunewyrmPartComponent implements Component<EntityStore> {

    public static final float SCALE_REFRESH_SECONDS = 30f;

    @Nonnull
    public static ComponentType<EntityStore, DunewyrmPartComponent> getComponentType() {
        return TitanRegistry.getDunewyrmPartComponentType();
    }

    @Nullable
    private Ref<EntityStore> owner;
    private int segmentIndex;
    @Nonnull
    private final Vector3d localOffset = new Vector3d();
    private int blockRotation;
    private float scale = 1f;
    private boolean climbable;
    private float scaleRefreshTimer = SCALE_REFRESH_SECONDS;
    private boolean spawnScalePassPending = true;
    private float spawnScalePassTimer = -1f;

    @Nonnull
    private final Vector3d sentPosition = new Vector3d();
    @Nonnull
    private final Rotation3f sentRotation = new Rotation3f();
    private boolean everSent;
    private final float syncPhase = ThreadLocalRandom.current().nextFloat();
    private float syncTimer = -1f;

    public DunewyrmPartComponent() {
    }

    public DunewyrmPartComponent(@Nonnull final Ref<EntityStore> owner,
                                 final int segmentIndex,
                                 @Nonnull final Vector3d localOffset,
                                 final int blockRotation,
                                 final float scale) {
        this(owner, segmentIndex, localOffset, blockRotation, scale, false);
    }

    public DunewyrmPartComponent(@Nonnull final Ref<EntityStore> owner,
                                 final int segmentIndex,
                                 @Nonnull final Vector3d localOffset,
                                 final int blockRotation,
                                 final float scale,
                                 final boolean climbable) {
        this.owner = owner;
        this.segmentIndex = segmentIndex;
        this.localOffset.set(localOffset);
        this.blockRotation = blockRotation;
        this.scale = scale;
        this.climbable = climbable;
    }

    @Nullable
    public Ref<EntityStore> getOwner() {
        return owner;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(final int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    @Nonnull
    public Vector3d getLocalOffset() {
        return localOffset;
    }

    public int getBlockRotation() {
        return blockRotation;
    }

    public float getScale() {
        return scale;
    }

    public boolean isClimbable() {
        return climbable;
    }

    public void setClimbable(final boolean climbable) {
        this.climbable = climbable;
    }

    public boolean consumeSpawnScalePass(final float dt, final float delay, final float stagger) {
        if (!spawnScalePassPending) return false;
        if (spawnScalePassTimer < 0f) spawnScalePassTimer = delay + syncPhase * stagger;
        spawnScalePassTimer -= dt;
        if (spawnScalePassTimer > 0f) return false;
        spawnScalePassPending = false;
        return true;
    }

    public boolean consumeScaleRefresh(final float dt, final float interval) {
        scaleRefreshTimer -= dt;
        if (scaleRefreshTimer > 0f) return false;
        scaleRefreshTimer = interval;
        return true;
    }

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

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new DunewyrmPartComponent();
        copy.owner = owner;
        copy.segmentIndex = segmentIndex;
        copy.localOffset.set(localOffset);
        copy.blockRotation = blockRotation;
        copy.scale = scale;
        copy.scaleRefreshTimer = scaleRefreshTimer;
        copy.spawnScalePassPending = spawnScalePassPending;
        copy.spawnScalePassTimer = spawnScalePassTimer;
        copy.sentPosition.set(sentPosition);
        copy.sentRotation.set(sentRotation);
        copy.everSent = everSent;
        copy.syncTimer = syncTimer;
        return copy;
    }
}
