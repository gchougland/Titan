package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hexvane.titan.anim.TitanAnimator;
import com.hexvane.titan.anim.TitanPose;
import com.hexvane.titan.asset.TitanIkChainDef;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.ik.FootState;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The brain and skeleton state of one titan, living on an otherwise invisible root entity. Every voxel and
 * ore node is a separate entity that reads its transform back out of {@link #getPose()}.
 *
 * <p>Runtime-only by design. A titan is a cluster of ~150 entities held together by nothing but this
 * component, so persisting it half-built would leave orphans; {@code /titan spawn} rebuilds instead.
 */
public final class TitanComponent implements Component<EntityStore> {

    public static ComponentType<EntityStore, TitanComponent> getComponentType() {
        return TitanRegistry.getTitanComponentType();
    }

    @Nullable
    private String variantId;
    private transient TitanVariantAsset variant;
    private transient TitanSkeletonAsset skeleton;

    private transient TitanPose pose;
    private transient TitanAnimator animator;

    @Nonnull
    private TitanState state = TitanState.SLEEPING;
    private float stateTime;
    /** Set by the AI when it wants the animation system to restart the state's clip. */
    private boolean clipDirty = true;

    private double scale = 1.0;
    private float yaw;
    @Nonnull
    private final Vector3d velocity = new Vector3d();

    @Nullable
    private Ref<EntityStore> target;
    private float attackCooldown;
    /** {@code -1} for the left arm, {@code +1} for the right. */
    private int attackSide = 1;
    private boolean impactFired;
    @Nonnull
    private final Vector3d attackPoint = new Vector3d();

    @Nonnull
    private FootState[] feet = new FootState[0];
    @Nonnull
    private int[] footChains = new int[0];
    /** World-space IK goals for hand chains; index matches {@link #handChains}. */
    @Nonnull
    private Vector3d[] handGoals = new Vector3d[0];
    @Nonnull
    private float[] handWeights = new float[0];
    @Nonnull
    private int[] handChains = new int[0];

    private int weakpointsTotal;
    private int weakpointsRemaining;
    /** Ore nodes, kept so death can pop the survivors and the debug command can list them. */
    @Nonnull
    private final List<Ref<EntityStore>> weakpoints = new ArrayList<>();

    private float deathTimer;
    private boolean lootDropped;

    public TitanComponent() {
    }

    public TitanComponent(@Nonnull final TitanVariantAsset variant, @Nonnull final TitanSkeletonAsset skeleton) {
        this.variantId = variant.getId();
        this.variant = variant;
        this.skeleton = skeleton;
        this.scale = skeleton.getUnitScale() * variant.getBodyScale();
        this.pose = new TitanPose(skeleton.getBoneCount());
        this.animator = new TitanAnimator(skeleton.getBoneCount());
        this.pose.resetToBind(skeleton);
        this.weakpointsTotal = variant.getWeakpointCount();
        this.weakpointsRemaining = variant.getWeakpointCount();
        initChains(skeleton);
    }

    private void initChains(@Nonnull final TitanSkeletonAsset skeleton) {
        final var chains = skeleton.getIkChains();
        int footCount = 0;
        int handCount = 0;
        for (final var chain : chains) {
            if (chain.getRole() == TitanIkChainDef.Role.FOOT) footCount++;
            else handCount++;
        }

        feet = new FootState[footCount];
        footChains = new int[footCount];
        handGoals = new Vector3d[handCount];
        handWeights = new float[handCount];
        handChains = new int[handCount];

        int f = 0;
        int h = 0;
        for (int i = 0; i < chains.length; i++) {
            final var chain = chains[i];
            if (chain.getRole() == TitanIkChainDef.Role.FOOT) {
                final var foot = new FootState();
                // Two alternating groups produce the diagonal gait; the phase is authored per limb.
                foot.gaitGroup = Math.round(chain.getGaitPhase() * 2f) % 2;
                feet[f] = foot;
                footChains[f] = i;
                f++;
            } else {
                handGoals[h] = new Vector3d();
                handChains[h] = i;
                h++;
            }
        }
    }

    @Nullable
    public String getVariantId() {
        return variantId;
    }

    @Nullable
    public TitanVariantAsset getVariant() {
        return variant;
    }

    @Nullable
    public TitanSkeletonAsset getSkeleton() {
        return skeleton;
    }

    /** {@code null} until the titan has been fully assembled by the spawner. */
    @Nullable
    public TitanPose getPose() {
        return pose;
    }

    @Nullable
    public TitanAnimator getAnimator() {
        return animator;
    }

    /** World blocks per model unit. */
    public double getScale() {
        return scale;
    }

    @Nonnull
    public TitanState getState() {
        return state;
    }

    public float getStateTime() {
        return stateTime;
    }

    public void addStateTime(final float dt) {
        stateTime += dt;
    }

    public void setState(@Nonnull final TitanState next) {
        if (state == next) return;
        state = next;
        stateTime = 0f;
        impactFired = false;
        clipDirty = true;
    }

    /** Consumed by the animation system to know when to (re)start the state clip. */
    public boolean consumeClipDirty() {
        if (!clipDirty) return false;
        clipDirty = false;
        return true;
    }

    public void markClipDirty() {
        clipDirty = true;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(final float yaw) {
        this.yaw = yaw;
    }

    @Nonnull
    public Vector3d getVelocity() {
        return velocity;
    }

    @Nullable
    public Ref<EntityStore> getTarget() {
        return target;
    }

    public void setTarget(@Nullable final Ref<EntityStore> target) {
        this.target = target;
    }

    public float getAttackCooldown() {
        return attackCooldown;
    }

    public void setAttackCooldown(final float attackCooldown) {
        this.attackCooldown = attackCooldown;
    }

    public void tickAttackCooldown(final float dt) {
        if (attackCooldown > 0f) attackCooldown = Math.max(0f, attackCooldown - dt);
    }

    public int getAttackSide() {
        return attackSide;
    }

    public void setAttackSide(final int attackSide) {
        this.attackSide = attackSide;
    }

    public boolean isImpactFired() {
        return impactFired;
    }

    public void setImpactFired(final boolean impactFired) {
        this.impactFired = impactFired;
    }

    /** Ground point the current smash is aimed at. */
    @Nonnull
    public Vector3d getAttackPoint() {
        return attackPoint;
    }

    @Nonnull
    public FootState[] getFeet() {
        return feet;
    }

    /** Index into {@link TitanSkeletonAsset#getIkChains()} for each entry of {@link #getFeet()}. */
    @Nonnull
    public int[] getFootChains() {
        return footChains;
    }

    @Nonnull
    public Vector3d[] getHandGoals() {
        return handGoals;
    }

    /** Blend between the clip pose ({@code 0}) and the IK goal ({@code 1}) for each hand chain. */
    @Nonnull
    public float[] getHandWeights() {
        return handWeights;
    }

    @Nonnull
    public int[] getHandChains() {
        return handChains;
    }

    /** Index into {@link #getHandChains()} matching the arm currently swinging, or {@code -1}. */
    public int findHandChainForSide(@Nonnull final TitanSkeletonAsset skeleton, final int side) {
        for (int i = 0; i < handChains.length; i++) {
            final var chain = skeleton.getIkChains()[handChains[i]];
            if (Math.signum(chain.getSide()) == Math.signum(side)) return i;
        }
        return handChains.length > 0 ? 0 : -1;
    }

    public int getWeakpointsTotal() {
        return weakpointsTotal;
    }

    public int getWeakpointsRemaining() {
        return weakpointsRemaining;
    }

    /**
     * Corrects the tally to the number of ore nodes that actually spawned. Without this a variant whose
     * model asset is missing would count nodes that do not exist and could never be killed.
     */
    public void setWeakpointCount(final int count) {
        weakpointsTotal = count;
        weakpointsRemaining = count;
    }

    /**
     * Records a destroyed ore node.
     *
     * <p>Synchronised because ore nodes are separate entities and several can be ticked in parallel; an
     * interleaved read-modify-write here would lose a decrement and leave the titan unkillable.
     *
     * @return {@code true} when that was the last one and the titan should die
     */
    public synchronized boolean consumeWeakpoint() {
        if (weakpointsRemaining > 0) weakpointsRemaining--;
        return weakpointsRemaining <= 0;
    }

    @Nonnull
    public List<Ref<EntityStore>> getWeakpoints() {
        return weakpoints;
    }

    public float getDeathTimer() {
        return deathTimer;
    }

    public void addDeathTimer(final float dt) {
        deathTimer += dt;
    }

    public boolean isLootDropped() {
        return lootDropped;
    }

    public void setLootDropped(final boolean lootDropped) {
        this.lootDropped = lootDropped;
    }

    /**
     * Re-resolves asset references after a live asset reload replaced the underlying objects.
     *
     * @return {@code false} if the variant or skeleton no longer exists, in which case the titan should be removed
     */
    public boolean refreshAssets() {
        if (variantId == null) return false;
        final var reloadedVariant = TitanVariantAsset.find(variantId);
        if (reloadedVariant == null) return false;
        final var reloadedSkeleton = TitanSkeletonAsset.find(reloadedVariant.getSkeleton());
        if (reloadedSkeleton == null) return false;

        variant = reloadedVariant;
        // Keep the existing pose: a reload that changes the bone count would invalidate every part entity,
        // so those titans are left on their original skeleton until they are respawned.
        if (skeleton == null || skeleton.getBoneCount() == reloadedSkeleton.getBoneCount()) {
            skeleton = reloadedSkeleton;
        }
        return pose != null && animator != null;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        // Cloning a titan would duplicate the root without its parts; hand back a fresh shell instead.
        final var copy = new TitanComponent();
        copy.variantId = variantId;
        return copy;
    }
}
