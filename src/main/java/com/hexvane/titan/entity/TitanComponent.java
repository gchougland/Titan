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
import com.hypixel.hytale.component.Store;
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

    /** {@link #getSiteCell()} for a titan that does not belong to a natural spawn site. */
    public static final long NO_SITE = Long.MIN_VALUE;

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
    /** Whether the pose was recomputed this tick; the parts and ore nodes skip their sync when it was not. */
    private boolean poseDirty = true;
    private float sleepPoseTimer;

    private long siteCell = NO_SITE;

    private double scale = 1.0;
    private float yaw;
    @Nonnull
    private final Vector3d velocity = new Vector3d();
    /** Where the titan was built, and the centre of the ground it will defend. */
    @Nonnull
    private final Vector3d home = new Vector3d();

    @Nullable
    private Ref<EntityStore> target;
    /** Set by the damage watcher, consumed by the AI on its next tick. */
    @Nullable
    private Ref<EntityStore> pendingAttacker;
    private float provokedTimer;
    private float attackCooldown;
    /** Kept apart from the ordinary cooldown so being climbed cannot answer itself. See the variant field. */
    private float plowCooldown;
    /** {@code -1} for the left arm, {@code +1} for the right. */
    private int attackSide = 1;
    private boolean impactFired;
    @Nonnull
    private final Vector3d attackPoint = new Vector3d();
    private float telegraphTimer;

    /** Index into {@link #feet} of the leg currently stomping, or {@code -1}. */
    private int stompFoot = -1;
    @Nonnull
    private final Vector3d stompGoal = new Vector3d();

    @Nonnull
    private final Vector3d wanderGoal = new Vector3d();
    private boolean wandering;
    private float wanderPause;

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
    private int weakpointsToKill;
    private int weakpointsBroken;
    private float nodeHealth;
    /** Players currently shown this titan's boss bar, so it can be taken off them again. */
    @Nonnull
    private final List<Ref<EntityStore>> barViewers = new ArrayList<>();
    /**
     * Ore nodes still expected to be in the world. Entries leave either because the node was confirmed
     * broken or because it went missing; {@link #auditWeakpoints} exists to tell those two apart.
     */
    @Nonnull
    private final List<Ref<EntityStore>> weakpoints = new ArrayList<>();
    private float lostGrace;

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
        // Nothing the size of a hill gets to pretend it is a rock formation, so a variant can opt out of
        // the disguise and be found already on its feet.
        this.state = variant.isStartAwake() ? TitanState.IDLE : TitanState.SLEEPING;
        // Left at zero until the spawner reports how many nodes it actually placed; the count is rolled at
        // spawn time, so the variant cannot supply it here.
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
        telegraphTimer = 0f;
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

    /**
     * The natural spawn site this titan was built for, or {@link #NO_SITE} if it was spawned by hand.
     *
     * <p>Only needed so that a death can be attributed back to a place. Whether a titan is standing
     * somewhere is worked out from the world seed every time, but the fact that one was killed there has
     * to be written down, and this is the link between the corpse and the record.
     */
    public long getSiteCell() {
        return siteCell;
    }

    public void setSiteCell(final long siteCell) {
        this.siteCell = siteCell;
    }

    /**
     * Whether the pose moved this tick. Set by the animation system and read by everything that copies
     * bone matrices onto entities, so a titan that did not move costs nothing downstream.
     */
    public boolean isPoseDirty() {
        return poseDirty;
    }

    public void setPoseDirty(final boolean poseDirty) {
        this.poseDirty = poseDirty;
    }

    /**
     * Rations how often a sleeping titan's pose is rebuilt.
     *
     * <p>Asleep, a titan is a boulder with a twelve-second breathing swell in it. Re-posing that twenty
     * times a second rewrites a couple of hundred voxel transforms and ships every one to every client in
     * range, for motion measured in fractions of a block. Running it a few times a second instead is
     * indistinguishable and is what makes it affordable to leave several of them standing around the world.
     *
     * @return seconds of animation to advance by, or {@code 0} when this tick should be skipped entirely
     */
    public float consumeSleepInterval(final float dt, final float interval) {
        sleepPoseTimer += dt;
        if (sleepPoseTimer < interval) return 0f;

        final float elapsed = sleepPoseTimer;
        sleepPoseTimer = 0f;
        return elapsed;
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

    /**
     * The spot the titan was built on. It will chase a player away from here but only so far, and it walks
     * back to it once it gives up, so a titan stays the landmark its spawn site made it.
     */
    @Nonnull
    public Vector3d getHome() {
        return home;
    }

    @Nullable
    public Ref<EntityStore> getTarget() {
        return target;
    }

    public void setTarget(@Nullable final Ref<EntityStore> target) {
        this.target = target;
    }

    /**
     * Notes whoever just hurt one of the ore nodes.
     *
     * <p>Written from the damage watcher, which runs off the AI's thread, and read back by the AI on its
     * next tick rather than acted on here. Waiting a tick costs nothing and means the state machine is
     * still the only thing that ever changes the titan's state.
     */
    public synchronized void reportAttacker(@Nonnull final Ref<EntityStore> attacker) {
        pendingAttacker = attacker;
    }

    @Nullable
    public synchronized Ref<EntityStore> consumePendingAttacker() {
        final Ref<EntityStore> attacker = pendingAttacker;
        pendingAttacker = null;
        return attacker;
    }

    /**
     * Whether the titan is still angry about being hit.
     *
     * <p>While it is, it holds onto its target out to its full leash range instead of the much shorter
     * distance it would otherwise lose interest at. Without that, shooting one from across a clearing would
     * wake it and then be forgotten on the very next tick.
     */
    public boolean isProvoked() {
        return provokedTimer > 0f;
    }

    public void provoke(final float seconds) {
        provokedTimer = Math.max(provokedTimer, seconds);
    }

    public void tickProvoked(final float dt) {
        if (provokedTimer > 0f) provokedTimer = Math.max(0f, provokedTimer - dt);
    }

    public float getAttackCooldown() {
        return attackCooldown;
    }

    public void setAttackCooldown(final float attackCooldown) {
        this.attackCooldown = attackCooldown;
    }

    public void tickAttackCooldown(final float dt) {
        if (attackCooldown > 0f) attackCooldown = Math.max(0f, attackCooldown - dt);
        if (plowCooldown > 0f) plowCooldown = Math.max(0f, plowCooldown - dt);
    }

    public float getPlowCooldown() {
        return plowCooldown;
    }

    public void setPlowCooldown(final float plowCooldown) {
        this.plowCooldown = plowCooldown;
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

    /**
     * A repeating clock for whatever the current state wants to do at intervals rather than every tick.
     *
     * <p>Two things use it and they never overlap. A windup flashes its marker on the ground, with the
     * interval shortening as the attack nears — how fast the ring is beating is how a player reads the
     * timing. A plough in progress uses it to space out the damage it shovels, since the blade sweeps its
     * own width several times a second and hitting on every tick would multiply the attack twentyfold.
     *
     * <p>Reset by {@link #setState}, so the first pulse of a state lands on the tick it begins.
     *
     * @return {@code true} when the interval has elapsed
     */
    public boolean consumePulse(final float dt, final float interval) {
        telegraphTimer -= dt;
        if (telegraphTimer > 0f) return false;
        telegraphTimer = interval;
        return true;
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

    /**
     * Index into {@link #getFeet()} of the leg currently mid-stomp, or {@code -1} when the gait owns them
     * all. The animation system checks this before handing a foot to the walk planner.
     */
    public int getStompFoot() {
        return stompFoot;
    }

    public void setStompFoot(final int stompFoot) {
        this.stompFoot = stompFoot;
    }

    /** World-space goal for the stomping foot, driven by the AI in place of the gait. */
    @Nonnull
    public Vector3d getStompGoal() {
        return stompGoal;
    }

    /**
     * Picks the leg nearest {@code point}, by where its foot is actually standing rather than by where the
     * rig says the leg is bolted on: the answer wanted is which foot has the least distance to travel.
     *
     * <p>Legs the gait has not planted yet are skipped. Their contact points are still at the origin, which
     * would otherwise read as the closest foot in the world and drop the attack there.
     *
     * @return an index into {@link #getFeet()}, or {@code -1} when no leg is standing on anything
     */
    public int findFootNearest(@Nonnull final Vector3d point) {
        int best = -1;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < feet.length; i++) {
            if (!feet[i].initialised) continue;
            final double dx = feet[i].current.x - point.x;
            final double dz = feet[i].current.z - point.z;
            final double distanceSq = dx * dx + dz * dz;
            if (distanceSq < bestSq) {
                bestSq = distanceSq;
                best = i;
            }
        }
        return best;
    }

    /** Where the titan is currently drifting towards, valid only while {@link #isWandering()}. */
    @Nonnull
    public Vector3d getWanderGoal() {
        return wanderGoal;
    }

    public boolean isWandering() {
        return wandering;
    }

    public void setWandering(final boolean wandering) {
        this.wandering = wandering;
    }

    /** Seconds left standing still before the next wander leg is rolled. */
    public float getWanderPause() {
        return wanderPause;
    }

    public void setWanderPause(final float wanderPause) {
        this.wanderPause = wanderPause;
    }

    public void tickWanderPause(final float dt) {
        if (wanderPause > 0f) wanderPause = Math.max(0f, wanderPause - dt);
    }

    /** Index into {@link #getHandChains()} matching the arm currently swinging, or {@code -1}. */
    public int findHandChainForSide(@Nonnull final TitanSkeletonAsset skeleton, final int side) {
        for (int i = 0; i < handChains.length; i++) {
            final var chain = skeleton.getIkChains()[handChains[i]];
            if (Math.signum(chain.getSide()) == Math.signum(side)) return i;
        }
        return handChains.length > 0 ? 0 : -1;
    }

    /**
     * How long a node may be missing before the titan concludes it is being torn down.
     *
     * <p>Long enough that a genuine kill can never be read as an unload. The death watcher and the node's
     * removal from the world are separate steps, and this covers the gap between them whatever order they
     * land in. Nothing about the titan changes while the clock runs.
     */
    private static final float LOST_GRACE_SECONDS = 2f;

    /** What {@link #auditWeakpoints} found. */
    public enum WeakpointStatus {
        /** Nodes still standing, or all accounted for and the titan is fighting on. */
        INTACT,
        /** Every node this titan spawned with has been confirmed destroyed. Time to die. */
        DESTROYED,
        /** A node left the world without ever being broken, so the rig is being torn down around it. */
        LOST
    }

    public int getWeakpointsTotal() {
        return weakpointsTotal;
    }

    /**
     * How many breaks it takes to kill this titan, which can be fewer than it carries.
     *
     * <p>Always at least one and never more than the number that actually spawned, so a variant asking for
     * more than its sockets could supply still dies rather than becoming invulnerable.
     */
    public int getWeakpointsToKill() {
        return weakpointsToKill;
    }

    /** Breaks still owed before the titan goes down. Zero once it is beaten. */
    public synchronized int getWeakpointsStillNeeded() {
        return Math.max(0, weakpointsToKill - weakpointsBroken);
    }

    /** Health of a single ore node at spawn, after the config multiplier. */
    public float getNodeHealth() {
        return nodeHealth;
    }

    public void setNodeHealth(final float nodeHealth) {
        this.nodeHealth = nodeHealth;
    }

    /**
     * The full length of the boss bar: what the nodes needed for a kill are worth, not every node on the
     * body. A titan carrying spares would otherwise show a bar that could never be emptied.
     */
    public float getTotalHealth() {
        return nodeHealth * weakpointsToKill;
    }

    @Nonnull
    public List<Ref<EntityStore>> getBarViewers() {
        return barViewers;
    }

    public synchronized int getWeakpointsRemaining() {
        return weakpointsTotal - weakpointsBroken;
    }

    /**
     * Corrects the tally to the number of ore nodes that actually spawned. Without this a variant whose
     * model asset is missing would count nodes that do not exist and could never be killed.
     *
     * @param toKill how many of them have to break, or anything {@code <= 0} for all of them. Clamped to
     *               what actually spawned, so a variant asking for more breaks than it has nodes is a
     *               harder fight rather than an unkillable one.
     */
    public synchronized void setWeakpointCount(final int count, final int toKill) {
        weakpointsTotal = count;
        weakpointsToKill = toKill <= 0 ? count : Math.min(toKill, count);
        weakpointsBroken = 0;
    }

    /**
     * Books in a node that was seen to die, so it stops being something the titan expects to find.
     *
     * <p>Called from the death watcher, which can fire for several nodes of the same titan on different
     * threads in one tick, hence the lock.
     */
    public synchronized void recordWeakpointBroken(@Nonnull final Ref<EntityStore> node) {
        if (!weakpoints.remove(node)) return;
        weakpointsBroken++;
    }

    /**
     * Decides whether the titan has been beaten, torn down, or neither.
     *
     * <p>The distinction is the whole point. A node can leave the world for reasons that have nothing to do
     * with a player: a titan is wide enough to straddle two chunk columns, and when one of them stops
     * ticking its nodes are destroyed while the root carries on. Reading that as a kill is what used to
     * leave a pile of ore sitting at an untouched spawn site with nobody around. So a death is only ever
     * credited from {@link #recordWeakpointBroken}, which fires on an actual death event, and a node that
     * simply goes missing is read as the rig coming apart instead.
     */
    @Nonnull
    public synchronized WeakpointStatus auditWeakpoints(@Nonnull final Store<EntityStore> store, final float dt) {
        // A titan that never managed to spawn a node is broken, not dead; killing it here would make the
        // failure look like a working boss that dies on sight.
        if (weakpointsTotal <= 0) return WeakpointStatus.INTACT;
        // Not every node, only the ones this variant asks for. Anything past that is a spare and the titan
        // goes down with it still attached.
        if (weakpointsBroken >= weakpointsToKill) return WeakpointStatus.DESTROYED;

        boolean missing = false;
        for (final Ref<EntityStore> ref : weakpoints) {
            if (ref == null
                || !ref.isValid()
                || store.getComponent(ref, TitanWeakpointComponent.getComponentType()) == null) {
                missing = true;
                break;
            }
        }

        if (!missing) {
            lostGrace = 0f;
            return WeakpointStatus.INTACT;
        }

        lostGrace += dt;
        return lostGrace >= LOST_GRACE_SECONDS ? WeakpointStatus.LOST : WeakpointStatus.INTACT;
    }

    /**
     * The nodes still expected to be in the world. Mutated under this component's lock by the death
     * watcher, so callers on the world thread should only read it.
     */
    @Nonnull
    public List<Ref<EntityStore>> getWeakpoints() {
        return weakpoints;
    }

    /**
     * Copies the surviving nodes into {@code out}, replacing whatever was there.
     *
     * <p>For readers that walk the list rather than glance at it. The death watcher removes entries under
     * this component's lock, which is enough to make a plain iteration on another thread throw partway
     * through; taking a copy under the same lock keeps the walk off the live list.
     */
    public synchronized void copyWeakpoints(@Nonnull final List<Ref<EntityStore>> out) {
        out.clear();
        out.addAll(weakpoints);
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
