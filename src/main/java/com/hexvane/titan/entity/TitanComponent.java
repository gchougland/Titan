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
import java.util.concurrent.ThreadLocalRandom;

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

    /**
     * How long a node may be missing before the titan concludes it is being torn down.
     *
     * <p>Long enough that a genuine kill is never read as an unload, since the death watcher and the node's
     * removal from the world are separate steps and may land in either order. Nothing about the titan
     * changes while the clock runs.
     */
    private static final float LOST_GRACE_SECONDS = 2f;

    /** Seconds the wobble phase clock wraps at. A whole number of cycles for any integer frequency. */
    private static final float WOBBLE_PERIOD = 60f;

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
    /** Phase clock for {@link #addWobbleTime}; see there for why it is not a wall clock. */
    private float wobbleTime = ThreadLocalRandom.current().nextFloat() * WOBBLE_PERIOD;
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
    /** Held separately from the ordinary cooldown so a plow cannot immediately follow another. */
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
    /** How far under their planted spots the feet are held; see {@link #getFootSink}. */
    private double footSink;

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
    /**
     * Players previously shown a custom boss bar. Unused after Encounter Manager took over presentation;
     * retained empty so older save/debug paths that touch the list stay safe.
     */
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

    /**
     * When set, an Encounter Manager + brain NPC Role own engagement UX and attack selection; this
     * component only executes {@link #intent}.
     */
    private boolean brainDriven;
    @Nullable
    private Ref<EntityStore> brainRef;
    @Nullable
    private Ref<EntityStore> encounterRef;
    @Nonnull
    private TitanIntent intent = TitanIntent.NONE;

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
        this.state = variant.isStartAwake() ? TitanState.IDLE : TitanState.SLEEPING;
        // The weakpoint tally stays at zero until the spawner reports how many nodes it placed, since the
        // count is rolled at spawn time.
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
     * <p>Site occupancy is derived from the world seed on demand, but a kill has to be recorded, so this
     * links the titan back to the site entry that stores it.
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
     * <p>A sleeping titan only carries a twelve-second breathing swell, so re-posing it twenty times a
     * second would rewrite hundreds of voxel transforms and replicate them all for motion measured in
     * fractions of a block. A few times a second is visually identical and far cheaper.
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

    /**
     * Advances the clock the procedural wobbles run off and returns it.
     *
     * <p>Kept per titan rather than read off a wall clock so two of the same variant standing together are
     * not in lockstep, and so a titan being posed at a reduced rate advances by what it was actually given.
     * Wrapped at a whole number of seconds, which is a whole number of cycles for any integer frequency, so
     * the sway does not jump when the counter rolls over and the value never grows large enough to lose
     * precision.
     */
    public float addWobbleTime(final float advance) {
        wobbleTime = (wobbleTime + advance) % WOBBLE_PERIOD;
        return wobbleTime;
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
     * The spot the titan was built on. It leashes to this point and walks back once it gives up a chase,
     * so it stays at the site it spawned on.
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
     * <p>Written by the damage watcher, which runs off the AI's thread, and consumed by the AI on its next
     * tick. Deferring by one tick keeps the state machine the only writer of the titan's state.
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
     * <p>While provoked it holds its target out to the full leash range rather than the much shorter range
     * it normally loses interest at, so a titan shot from across a clearing stays engaged.
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
     * <p>Two states use it and they never overlap. A windup flashes its ground marker on a shortening
     * interval, which is how the player reads the remaining time. A plow in progress uses it to space out
     * the damage it deals, since the blade sweeps its own width several times a second and damaging on
     * every tick would multiply the attack twentyfold.
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
     * How far below the ground the feet are held, in world blocks.
     *
     * <p>Zero for anything walking about: the planner puts each foot on the surface and the legs reach
     * down to it. A titan that sits lower than its legs can fold sets this instead, and then the whole
     * rig — hips and feet together — goes down into the ground keeping its shape, rather than the legs
     * folding further than they are able and the inverse kinematics turning them back up through the body
     * to reach feet still standing on the surface.
     */
    public double getFootSink() {
        return footSink;
    }

    public void setFootSink(final double footSink) {
        this.footSink = footSink;
    }

    /**
     * Picks the leg nearest {@code point}, measured from where the foot is standing rather than where the
     * leg attaches, so the result is the foot with the least distance to travel.
     *
     * <p>Legs the gait has not planted yet are skipped, since their contact points are still at the origin
     * and would otherwise always measure as nearest.
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
     * <p>Clamped to at least one and to no more than the number that actually spawned, so a variant asking
     * for more breaks than it has sockets is still killable.
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
     * The full length of the boss bar, counting only the nodes needed for a kill. Including spares would
     * show a bar that could never be emptied.
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
     * Corrects the tally to the number of ore nodes that actually spawned, so a variant with a missing
     * model asset does not count nodes that do not exist and become unkillable.
     *
     * @param toKill how many must break, or {@code <= 0} for all of them. Clamped to the number that
     *               spawned.
     */
    public synchronized void setWeakpointCount(final int count, final int toKill) {
        weakpointsTotal = count;
        weakpointsToKill = toKill <= 0 ? count : Math.min(toKill, count);
        weakpointsBroken = 0;
    }

    /**
     * Records a node that was seen to die, removing it from the set the titan expects to find.
     *
     * <p>Called from the death watcher, which can fire for several nodes of one titan on different threads
     * in the same tick, hence the lock.
     */
    public synchronized void recordWeakpointBroken(@Nonnull final Ref<EntityStore> node) {
        if (!weakpoints.remove(node)) return;
        weakpointsBroken++;
    }

    /**
     * Decides whether the titan has been beaten, torn down, or neither.
     *
     * <p>A node can leave the world without any player involvement: a titan is wide enough to straddle two
     * chunk columns, and when one stops ticking its nodes are destroyed while the root continues. Treating
     * that as a kill would drop loot at an untouched spawn site, so a kill is only ever credited from
     * {@link #recordWeakpointBroken}, which fires on a real death event. A node that merely goes missing is
     * reported as {@link WeakpointStatus#LOST} instead.
     */
    @Nonnull
    public synchronized WeakpointStatus auditWeakpoints(@Nonnull final Store<EntityStore> store, final float dt) {
        // A titan that never spawned a node is misconfigured rather than dead, and killing it here would
        // disguise the failure as a boss that dies on sight.
        if (weakpointsTotal <= 0) return WeakpointStatus.INTACT;
        // Only the breaks the variant asks for. Any node beyond that is a spare and stays attached.
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
     * <p>For callers that iterate the list. The death watcher removes entries under this component's lock,
     * so iterating the live list from another thread can throw partway through. Copying under the same lock
     * avoids that.
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

    /** Whether an Encounter + brain NPC own engagement and attack selection for this titan. */
    public boolean isBrainDriven() {
        return brainDriven;
    }

    public void setBrainDriven(final boolean brainDriven) {
        this.brainDriven = brainDriven;
    }

    @Nullable
    public Ref<EntityStore> getBrainRef() {
        return brainRef;
    }

    public void setBrainRef(@Nullable final Ref<EntityStore> brainRef) {
        this.brainRef = brainRef;
    }

    @Nullable
    public Ref<EntityStore> getEncounterRef() {
        return encounterRef;
    }

    public void setEncounterRef(@Nullable final Ref<EntityStore> encounterRef) {
        this.encounterRef = encounterRef;
    }

    @Nonnull
    public TitanIntent getIntent() {
        return intent;
    }

    public void setIntent(@Nonnull final TitanIntent intent) {
        this.intent = intent;
    }

    /** Reads and clears the pending Role intent. */
    @Nonnull
    public TitanIntent consumeIntent() {
        final TitanIntent current = intent;
        intent = TitanIntent.NONE;
        return current;
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
        // Cloning would duplicate the root without its parts, so return an empty shell instead.
        final var copy = new TitanComponent();
        copy.variantId = variantId;
        return copy;
    }
}
