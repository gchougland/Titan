package com.hexvane.titan.system;

import com.hexvane.titan.ai.TitanAiScratch;
import com.hexvane.titan.ai.TitanAiSupport;
import com.hexvane.titan.ai.TitanBodyDriver;
import com.hexvane.titan.ai.TitanHurlAttack;
import com.hexvane.titan.ai.TitanMeleeAttack;
import com.hexvane.titan.ai.TitanPlowAttack;
import com.hexvane.titan.ai.TitanPoundAttack;
import com.hexvane.titan.ai.TitanSlamAttack;
import com.hexvane.titan.ai.TitanStompAttack;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanLoot;
import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanIntent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.ik.GroundSampler;
import com.hexvane.titan.spawn.TitanEnvironment;
import com.hexvane.titan.spawn.TitanSiteMemory;
import com.hexvane.titan.spawn.TitanTrio;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the titan state machine, its body movement and its choice of attack.
 *
 * <p>Runs before {@link TitanAnimationSystem} so the pose is built from the state decided this tick. The
 * attacks themselves live in {@code com.hexvane.titan.ai}, one class per move; this system decides which
 * one to start and ticks whichever is running.
 */
public final class TitanAiSystem extends EntityTickingSystem<EntityStore> {

    /** Seconds an idle arm takes to hand control back to the clip pose. */
    private static final float HAND_IK_FADE = 0.4f;
    /** Seconds the invisible root outlives its body. */
    private static final float DEATH_LINGER_SECONDS = 2f;
    /** How long a titan stays angry after being hit, holding its target out to the full leash range. */
    private static final float PROVOKED_SECONDS = 20f;
    /** How close to its spawn spot counts as home, in blocks. Roughly the width of the titan itself. */
    private static final double HOME_ARRIVAL_RADIUS = 3.0;
    /** How long a natural spawn site stays empty after its titan is killed, in seconds. */
    private static final float KILL_COOLDOWN = 900f;

    /** How close to a wander goal counts as having arrived, in blocks. */
    private static final double WANDER_ARRIVAL_RADIUS = 4.0;
    /** Shortest wander leg worth setting off on, in blocks. Below this the titan just shuffles in place. */
    private static final double WANDER_MIN_LEG = 12.0;
    /** Rolls per tick for somewhere to wander to before giving up and trying again next tick. */
    private static final int WANDER_GOAL_ATTEMPTS = 6;
    /**
     * Vertical search window for the ground under a candidate wander goal, in blocks.
     *
     * <p>The upward reach is deliberately short. The scan takes the first solid block from the top down and
     * a tree counts as solid, so reaching high enough to clear a canopy would put a goal in a wood fifteen
     * blocks above the dirt. Eight blocks covers terrain that rises inside a wander radius without catching
     * a tree.
     */
    private static final int WANDER_GROUND_ABOVE = 8;
    private static final int WANDER_GROUND_BELOW = 32;

    /** How far off the body's forward axis a target must be before the attacking side is reconsidered. */
    private static final double SIDE_PREFERENCE_DEADZONE = 0.5;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanComponent.getComponentType(), TransformComponent.getComponentType());

    /** Where a kill is written down, so the site stays empty across a restart. */
    @Nullable
    private final ResourceType<EntityStore, TitanSiteMemory> siteMemoryType;

    /** Per-tick working values. The AI is world-thread only, so one instance covers every titan. */
    @Nonnull
    private final TitanAiScratch scratch = new TitanAiScratch();

    public TitanAiSystem(@Nullable final ResourceType<EntityStore, TitanSiteMemory> siteMemoryType) {
        this.siteMemoryType = siteMemoryType;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(final float dt,
                     final int index,
                     @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final var titan = archetypeChunk.getComponent(index, TitanComponent.getComponentType());
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (titan == null || transform == null) return;

        if (!titan.refreshAssets()) {
            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
            return;
        }

        final TitanVariantAsset variant = titan.getVariant();
        final TitanSkeletonAsset skeleton = titan.getSkeleton();
        if (variant == null || skeleton == null) return;

        // A pet has no part in any of this. Its behaviour, its ground follow and its own upkeep are all
        // owned by YagaPetSystem, and letting the combat machine tick it too would have two systems
        // choosing a state and a velocity for the same body on the same tick.
        if (variant.isPet()) return;

        final Ref<EntityStore> self = archetypeChunk.getReferenceTo(index);
        titan.addStateTime(dt);
        titan.tickAttackCooldown(dt);

        if (titan.getState() == TitanState.DYING) {
            titan.getVelocity().set(0);
            tickDying(dt, commandBuffer, self, titan, variant, transform);
            return;
        }

        // Checked before anything else can change the state, so a titan whose last ore node just broke
        // cannot slip in another attack on the way down.
        switch (titan.auditWeakpoints(store, dt)) {
            case DESTROYED -> {
                titan.getVelocity().set(0);
                titan.setState(TitanState.DYING);
                TitanSound.play(commandBuffer, variant.getDeathSound(), transform.getPosition());
                recordKill(store, titan);
                TitanTrio.detach(store, titan);
                return;
            }
            case LOST -> {
                // Part of the rig has gone out from under it, so this is an unload rather than a kill.
                // Dying here would drop loot at a site no player has touched and mark it cleared for the
                // next quarter of an hour.
                TitanTrio.detach(store, titan);
                commandBuffer.removeEntity(self, RemoveReason.REMOVE);
                return;
            }
            case INTACT -> {
            }
        }

        titan.tickProvoked(dt);
        retaliate(commandBuffer, titan, variant, transform);

        if (titan.isBrainDriven()) {
            applyBrainWake(commandBuffer, titan, variant, transform);
        }

        final boolean hasTarget = resolveTarget(store, titan, variant, transform.getPosition());

        switch (titan.getState()) {
            case SLEEPING -> tickSleeping(commandBuffer, titan, variant, transform, hasTarget);
            case WAKING -> tickWaking(titan);
            case IDLE -> tickIdle(store, commandBuffer, titan, variant, transform, dt, hasTarget);
            case CHASE -> tickChase(store, commandBuffer, titan, variant, skeleton, transform, dt, hasTarget);
            case WINDUP -> TitanMeleeAttack.tickWindup(scratch, store, commandBuffer, titan, variant, transform, dt);
            case SMASH -> TitanMeleeAttack.tickSmash(store, commandBuffer, self, titan, variant);
            case STUNNED -> TitanMeleeAttack.tickStunned(titan, variant);
            case RECOVER -> TitanMeleeAttack.tickRecover(titan, variant);
            case SLAM_WINDUP -> TitanSlamAttack.tickWindup(scratch, store, commandBuffer, titan, variant, transform, dt);
            case SLAM -> TitanSlamAttack.tickSlam(store, commandBuffer, self, titan, variant);
            case PRONE -> TitanSlamAttack.tickProne(titan, variant);
            case RISING -> TitanSlamAttack.tickRising(titan, variant);
            case POUND_WINDUP -> TitanPoundAttack.tickWindup(scratch, store, commandBuffer, titan, variant, transform, dt);
            case POUND -> TitanPoundAttack.tickPound(store, commandBuffer, self, titan, variant);
            case POUND_STUNNED -> TitanPoundAttack.tickStunned(titan, variant);
            case POUND_RECOVER -> TitanPoundAttack.tickRecover(titan, variant);
            case HURL_WINDUP -> TitanHurlAttack.tickWindup(scratch, store, commandBuffer, titan, variant, transform, dt);
            case HURL -> TitanHurlAttack.tickHurl(scratch, store, commandBuffer, self, titan, variant, skeleton, transform);
            case HURL_RECOVER -> TitanHurlAttack.tickRecover(titan, variant);
            case PLOW_WINDUP -> TitanPlowAttack.tickWindup(scratch, store, commandBuffer, titan, variant, transform, dt);
            case PLOW -> TitanPlowAttack.tickPlow(scratch, store, commandBuffer, self, titan, variant, skeleton, transform, dt);
            case PLOW_RECOVER -> TitanPlowAttack.tickRecover(titan, variant, transform);
            case STOMP_WINDUP -> TitanStompAttack.tickWindup(scratch, store, commandBuffer, titan, variant, transform, dt);
            case STOMP -> TitanStompAttack.tickStomp(store, commandBuffer, self, titan, variant);
            case STOMP_RECOVER -> TitanStompAttack.tickRecover(titan, variant);
            case EMOTING -> titan.getVelocity().set(0);
            default -> {
            }
        }

        updateHandGoals(titan, skeleton, dt);
        TitanBodyDriver.settleBodyHeight(store, transform, dt, 0);
        transform.getRotation().setYaw(titan.getYaw());
    }

    /** Consumes a Role WAKE intent while still sleeping. */
    private static void applyBrainWake(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                       @Nonnull final TitanComponent titan,
                                       @Nonnull final TitanVariantAsset variant,
                                       @Nonnull final TransformComponent transform) {
        if (titan.getState() != TitanState.SLEEPING) return;
        if (titan.getIntent() != TitanIntent.WAKE) return;
        titan.consumeIntent();
        titan.setState(TitanState.WAKING);
        TitanSound.play(commandBuffer, variant.getWakeSound(), transform.getPosition());
    }

    /**
     * Points the arm IK goals at whatever the current attack needs, and fades every arm the attack is not
     * using back to its clip pose.
     */
    private static void updateHandGoals(@Nonnull final TitanComponent titan,
                                        @Nonnull final TitanSkeletonAsset skeleton,
                                        final float dt) {

        final TitanState state = titan.getState();

        if (state.isBodySlam()) {
            TitanSlamAttack.applyHandGoals(titan, skeleton);
            return;
        }
        if (state.isGroundPound()) {
            TitanPoundAttack.applyHandGoals(titan, skeleton);
            return;
        }
        if (state.isHeadPlow()) {
            TitanPlowAttack.applyHandGoals(titan, skeleton);
            return;
        }

        final float[] weights = titan.getHandWeights();
        final int active = state.isOneArmed()
            ? titan.findHandChainForSide(skeleton, titan.getAttackSide())
            : -1;

        for (int i = 0; i < weights.length; i++) {
            if (i != active) {
                weights[i] = Math.max(0f, weights[i] - dt / HAND_IK_FADE);
            }
        }
        if (active < 0) return;

        final double raise = skeleton.getHipHeight() * titan.getScale() * TitanAiSupport.RAISED_HAND_HEIGHT_FACTOR;
        final var goal = titan.getHandGoals()[active];
        final var impact = titan.getAttackPoint();

        if (state.isBoulderThrow()) {
            TitanHurlAttack.applyHandGoal(titan, goal, impact, raise, weights, active);
        } else {
            TitanMeleeAttack.applyHandGoal(titan, goal, impact, raise, weights, active);
        }
    }

    /**
     * Turns a hit on an ore node into a target.
     *
     * <p>A titan has no health of its own, so damage to one of its nodes is the only signal that it is
     * under attack. Without this a sleeping titan could be dismantled from a distance.
     */
    private static void retaliate(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final TransformComponent transform) {

        final Ref<EntityStore> attacker = titan.consumePendingAttacker();
        if (attacker == null || !attacker.isValid()) return;

        titan.setTarget(attacker);
        titan.provoke(PROVOKED_SECONDS);

        if (titan.getState() == TitanState.SLEEPING) {
            titan.setState(TitanState.WAKING);
            TitanSound.play(commandBuffer, variant.getWakeSound(), transform.getPosition());
        }
    }

    /**
     * Picks or drops the current target.
     *
     * @return {@code true} when {@code scratch.targetPosition} now holds a live target's position
     */
    private boolean resolveTarget(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final Vector3d position) {

        // A passive titan never looks for a target and can be stood next to or climbed on indefinitely.
        // Only being hit gives it one, and only for as long as the provocation lasts.
        if (variant.isPassive() && !titan.isProvoked()) {
            titan.setTarget(null);
            return false;
        }

        // Freshly hit, the titan holds its target out to the edge of the ground it defends rather than the
        // much shorter range it normally loses interest at.
        final double keepRadius = titan.isProvoked()
            ? Math.max(variant.getLoseTargetRadius(), variant.getLeashRadius())
            : variant.getLoseTargetRadius();

        final Ref<EntityStore> existing = titan.getTarget();
        if (existing != null && existing.isValid()) {
            final var transform = store.getComponent(existing, TransformComponent.getComponentType());
            if (transform != null
                && transform.getPosition().distance(position) <= keepRadius
                && isWithinLeash(titan, variant, transform.getPosition())) {
                scratch.targetPosition.set(transform.getPosition());
                return true;
            }
        }

        final double searchRadius = titan.getState() == TitanState.SLEEPING
            ? variant.getWakeRadius()
            : variant.getLoseTargetRadius();

        final Ref<EntityStore> found = findNearestPlayer(store, position, searchRadius);
        if (found == null) {
            titan.setTarget(null);
            return false;
        }

        final var transform = store.getComponent(found, TransformComponent.getComponentType());
        if (transform == null || !isWithinLeash(titan, variant, transform.getPosition())) {
            titan.setTarget(null);
            return false;
        }

        titan.setTarget(found);
        scratch.targetPosition.set(transform.getPosition());
        return true;
    }

    /**
     * Whether a point is on the ground this titan defends.
     *
     * <p>Measured from where the titan was built rather than where it currently stands, so a long chase
     * cannot walk it across the map a step at a time. {@link #tickIdle} brings it home afterwards.
     */
    private static boolean isWithinLeash(@Nonnull final TitanComponent titan,
                                         @Nonnull final TitanVariantAsset variant,
                                         @Nonnull final Vector3d point) {
        final double leash = variant.getLeashRadius();
        if (leash <= 0) return true;
        return TitanAiSupport.horizontalDistance(titan.getHome(), point) <= leash;
    }

    @Nullable
    private static Ref<EntityStore> findNearestPlayer(@Nonnull final Store<EntityStore> store,
                                                      @Nonnull final Vector3d position,
                                                      final double radius) {
        Ref<EntityStore> best = null;
        double bestDistance = Double.MAX_VALUE;

        for (final Ref<EntityStore> candidate : TargetUtil.getAllEntitiesInSphere(position, radius, store)) {
            if (!candidate.isValid()) continue;
            if (store.getComponent(candidate, Player.getComponentType()) == null) continue;

            final var transform = store.getComponent(candidate, TransformComponent.getComponentType());
            if (transform == null) continue;

            final double distance = transform.getPosition().distanceSquared(position);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private void tickSleeping(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull final TitanComponent titan,
                              @Nonnull final TitanVariantAsset variant,
                              @Nonnull final TransformComponent transform,
                              final boolean hasTarget) {
        // Brain Roles own proximity engage via Encounter ChangeTargetRole + TitanWake.
        if (titan.isBrainDriven()) return;
        if (!hasTarget) return;
        if (scratch.targetPosition.distance(transform.getPosition()) > variant.getWakeRadius()) return;

        titan.setState(TitanState.WAKING);
        TitanSound.play(commandBuffer, variant.getWakeSound(), transform.getPosition());
    }

    private static void tickWaking(@Nonnull final TitanComponent titan) {
        final var animator = titan.getAnimator();
        if (animator == null || animator.isFinished()) {
            titan.setState(TitanState.IDLE);
        }
    }

    /**
     * Nothing to fight. Walks back to the spawn spot if the last chase left it out of place, then stands
     * there until somebody comes near.
     *
     * <p>A variant with a wander radius drifts between points inside that circle instead. See
     * {@link #tickWander}.
     */
    private void tickIdle(@Nonnull final Store<EntityStore> store,
                          @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                          @Nonnull final TitanComponent titan,
                          @Nonnull final TitanVariantAsset variant,
                          @Nonnull final TransformComponent transform,
                          final float dt,
                          final boolean hasTarget) {
        if (hasTarget) {
            titan.getVelocity().set(0);
            if (titan.isBrainDriven()) {
                final TitanSkeletonAsset skeleton = titan.getSkeleton();
                if (skeleton != null
                    && tryExecuteIntent(store, commandBuffer, titan, variant, skeleton, transform, dt)) {
                    return;
                }
            }
            titan.setState(TitanState.CHASE);
            return;
        }

        final var position = transform.getPosition();

        if (variant.getWanderRadius() > 0) {
            tickWander(store, titan, variant, position, dt);
            return;
        }

        if (TitanAiSupport.horizontalDistance(position, titan.getHome()) <= HOME_ARRIVAL_RADIUS) {
            titan.getVelocity().set(0);
            return;
        }
        TitanAiSupport.walkTowards(scratch, titan, variant, position, titan.getHome(), HOME_ARRIVAL_RADIUS, dt);
    }

    /**
     * Drifts between points inside the wander circle, standing a while at each.
     *
     * <p>The pause is what makes it read as wandering rather than patrolling. A leg that fails its checks
     * costs one tick and is rolled again on the next, so a titan boxed in by unsuitable ground stands still
     * until a roll finds a way out.
     */
    private void tickWander(@Nonnull final Store<EntityStore> store,
                            @Nonnull final TitanComponent titan,
                            @Nonnull final TitanVariantAsset variant,
                            @Nonnull final Vector3d position,
                            final float dt) {

        if (titan.isWandering()) {
            if (TitanAiSupport.horizontalDistance(position, titan.getWanderGoal()) > WANDER_ARRIVAL_RADIUS) {
                TitanAiSupport.walkTowards(scratch, titan, variant, position, titan.getWanderGoal(), WANDER_ARRIVAL_RADIUS, dt);
                return;
            }
            titan.setWandering(false);
            titan.setWanderPause(ThreadLocalRandom.current().nextFloat(
                Math.max(0f, variant.getWanderPauseMin()),
                Math.max(Math.max(0f, variant.getWanderPauseMin()) + 0.01f, variant.getWanderPauseMax())));
        }

        titan.getVelocity().set(0);
        titan.tickWanderPause(dt);
        if (titan.getWanderPause() > 0f) return;

        if (rollWanderGoal(store, titan, variant, position)) {
            titan.setWandering(true);
        }
    }

    /**
     * Picks somewhere inside the wander circle to walk to next.
     *
     * <p>A candidate is rejected if it is too close to be worth walking to, if the ground under it is not
     * loaded, or if it falls outside the variant's environments.
     *
     * @return whether a goal was found
     */
    private boolean rollWanderGoal(@Nonnull final Store<EntityStore> store,
                                   @Nonnull final TitanComponent titan,
                                   @Nonnull final TitanVariantAsset variant,
                                   @Nonnull final Vector3d position) {

        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        final var random = ThreadLocalRandom.current();
        final double radius = variant.getWanderRadius();

        // The environment filter only applies to a titan already standing in a matching environment. One
        // spawned by command outside its biome would otherwise fail every roll and never move at all.
        final boolean fenced = TitanEnvironment.matches(chunkStore, variant.getEnvironments(), position);

        for (int attempt = 0; attempt < WANDER_GOAL_ATTEMPTS; attempt++) {
            // Square-rooted so the points spread evenly over the disc rather than bunching at the middle.
            final double distance = radius * Math.sqrt(random.nextDouble());
            final double angle = random.nextDouble() * Math.PI * 2;
            scratch.point.set(
                titan.getHome().x + Math.cos(angle) * distance,
                titan.getHome().y,
                titan.getHome().z + Math.sin(angle) * distance
            );

            if (TitanAiSupport.horizontalDistance(position, scratch.point) < WANDER_MIN_LEG) continue;

            final double ground = GroundSampler.sample(
                chunkStore, scratch.point.x, scratch.point.y, scratch.point.z, WANDER_GROUND_ABOVE, WANDER_GROUND_BELOW);
            if (!GroundSampler.isValid(ground)) continue;
            scratch.point.y = ground;

            if (fenced && !TitanEnvironment.matches(chunkStore, variant.getEnvironments(), scratch.point)) continue;

            titan.getWanderGoal().set(scratch.point);
            return true;
        }
        return false;
    }

    /**
     * Walks towards the target and decides what to do on arrival.
     *
     * <p>Three checks in order: a rider on the back calls for a plow and nothing else, a target in reach
     * calls for one of the melee attacks, and a target beyond reach but within throwing range is answered
     * with a boulder rather than a walk.
     */
    private void tickChase(@Nonnull final Store<EntityStore> store,
                           @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                           @Nonnull final TitanComponent titan,
                           @Nonnull final TitanVariantAsset variant,
                           @Nonnull final TitanSkeletonAsset skeleton,
                           @Nonnull final TransformComponent transform,
                           final float dt,
                           final boolean hasTarget) {
        if (!hasTarget) {
            titan.getVelocity().set(0);
            titan.setState(TitanState.IDLE);
            return;
        }

        final var position = transform.getPosition();

        if (titan.isBrainDriven()) {
            if (tryExecuteIntent(store, commandBuffer, titan, variant, skeleton, transform, dt)) {
                return;
            }
        } else if (titan.getAttackCooldown() <= 0f) {
            if (TitanPlowAttack.tryBegin(scratch, store, commandBuffer, titan, variant, skeleton, transform)) return;

            final double distance = TitanAiSupport.horizontalDistance(position, scratch.targetPosition);

            if (distance <= variant.getAttackRange()) {
                TitanAiSupport.turnTowards(titan, position, scratch.targetPosition, variant.getTurnSpeed(), dt);
                beginMelee(commandBuffer, titan, variant, position);
                titan.getVelocity().set(0);
                return;
            }

            if (distance >= variant.getHurlMinRange() && distance <= variant.getHurlMaxRange()
                && ThreadLocalRandom.current().nextFloat() < variant.getHurlChance()) {
                TitanAiSupport.turnTowards(titan, position, scratch.targetPosition, variant.getTurnSpeed(), dt);
                titan.setAttackSide(chooseAttackSide(titan, position));
                titan.setState(TitanState.HURL_WINDUP);
                TitanSound.play(commandBuffer, variant.getTelegraphSound(), position);
                titan.getVelocity().set(0);
                return;
            }
        }

        // A titan that does not chase holds its ground and turns to watch, so walking away from one is a
        // genuine escape. It stays in CHASE rather than dropping to IDLE because the target is still live,
        // and bouncing between the two would restart the clip every tick.
        if (!variant.isChase()) {
            TitanAiSupport.turnTowards(titan, position, scratch.targetPosition, variant.getTurnSpeed(), dt);
            titan.getVelocity().set(0);
            return;
        }

        TitanAiSupport.walkTowards(scratch, titan, variant, position, scratch.targetPosition, variant.getAttackRange(), dt);
    }

    /**
     * Starts an attack queued by the brain Role. Returns {@code true} when an attack began this tick.
     */
    private boolean tryExecuteIntent(@Nonnull final Store<EntityStore> store,
                                     @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull final TitanComponent titan,
                                     @Nonnull final TitanVariantAsset variant,
                                     @Nonnull final TitanSkeletonAsset skeleton,
                                     @Nonnull final TransformComponent transform,
                                     final float dt) {
        final TitanIntent intent = titan.getIntent();
        if (intent == TitanIntent.NONE || intent == TitanIntent.WAKE || intent == TitanIntent.CHASE) {
            if (intent == TitanIntent.CHASE) titan.consumeIntent();
            return false;
        }
        if (titan.getAttackCooldown() > 0f) return false;

        final var position = transform.getPosition();
        TitanAiSupport.turnTowards(titan, position, scratch.targetPosition, variant.getTurnSpeed(), dt);
        titan.consumeIntent();

        switch (intent) {
            case MELEE -> beginMelee(commandBuffer, titan, variant, position);
            case SLAM -> {
                titan.setState(TitanState.SLAM_WINDUP);
                TitanSound.play(commandBuffer, variant.getTelegraphSound(), position);
            }
            case POUND -> {
                titan.setState(TitanState.POUND_WINDUP);
                TitanSound.play(commandBuffer, variant.getTelegraphSound(), position);
            }
            case HURL -> {
                titan.setAttackSide(chooseAttackSide(titan, position));
                titan.setState(TitanState.HURL_WINDUP);
                TitanSound.play(commandBuffer, variant.getTelegraphSound(), position);
            }
            case PLOW -> TitanPlowAttack.tryBegin(scratch, store, commandBuffer, titan, variant, skeleton, transform);
            case STOMP -> {
                TitanStompAttack.begin(scratch, titan);
                TitanSound.play(commandBuffer, variant.getTelegraphSound(), position);
            }
            default -> {
                return false;
            }
        }
        titan.getVelocity().set(0);
        return true;
    }

    /**
     * Chooses between the melee attacks by weight.
     *
     * <p>The chances in a variant are weights against each other rather than probabilities, so raising one
     * does not silently take from another and writing {@code 2} against {@code 1} gives twice as many.
     * Zeroing one removes it from the rotation, which is how a titan with no arms is left with its legs.
     *
     * <p>A variant that has zeroed everything still gets the arm smash. That is a misconfiguration, and a
     * titan swinging an arm it does not have is easier to notice than one standing inert.
     */
    private void beginMelee(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                            @Nonnull final TitanComponent titan,
                            @Nonnull final TitanVariantAsset variant,
                            @Nonnull final Vector3d position) {

        final float smash = Math.max(0f, variant.getSmashChance());
        final float slam = Math.max(0f, variant.getSlamChance());
        final float pound = Math.max(0f, variant.getPoundChance());
        final float stomp = titan.getFeet().length > 0 ? Math.max(0f, variant.getStompChance()) : 0f;

        final float total = smash + slam + pound + stomp;
        final float roll = total <= 0f ? 0f : ThreadLocalRandom.current().nextFloat() * total;

        if (total > 0f && roll < slam) {
            titan.setState(TitanState.SLAM_WINDUP);
        } else if (total > 0f && roll < slam + pound) {
            titan.setState(TitanState.POUND_WINDUP);
        } else if (total > 0f && roll < slam + pound + stomp) {
            TitanStompAttack.begin(scratch, titan);
        } else {
            titan.setAttackSide(chooseAttackSide(titan, position));
            titan.setState(TitanState.WINDUP);
        }
        TitanSound.play(commandBuffer, variant.getTelegraphSound(), position);
    }

    /** Alternates arms, preferring the one the target is standing on the side of. */
    private int chooseAttackSide(@Nonnull final TitanComponent titan, @Nonnull final Vector3d position) {
        final double dx = scratch.targetPosition.x - position.x;
        final double dz = scratch.targetPosition.z - position.z;
        // Right vector at this yaw, used to work out which side of the body the target is on.
        final double rightX = Math.cos(titan.getYaw());
        final double rightZ = -Math.sin(titan.getYaw());
        final double side = dx * rightX + dz * rightZ;
        if (Math.abs(side) < SIDE_PREFERENCE_DEADZONE) return -titan.getAttackSide();
        return side >= 0 ? 1 : -1;
    }

    /**
     * Winds the boss down. The voxels have already been kicked loose by {@link TitanPartSyncSystem}, so the
     * root only has to spill the loot and then get out of the way.
     */
    private static void tickDying(final float dt,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull final Ref<EntityStore> self,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final TransformComponent transform) {

        titan.addDeathTimer(dt);

        if (!titan.isLootDropped()) {
            titan.setLootDropped(true);
            TitanLoot.drop(commandBuffer, variant, transform.getPosition());
        }

        // Long enough for every part to have seen one sync tick and detached itself.
        if (titan.getDeathTimer() >= DEATH_LINGER_SECONDS) {
            commandBuffer.removeEntity(self, RemoveReason.REMOVE);
        }
    }

    /**
     * Notes that a naturally sited titan has been beaten, so its spot stays empty across a restart.
     *
     * <p>Recorded at the moment the last ore node goes rather than by watching for the entity to disappear,
     * because the engine also removes titans for reasons that are not deaths, such as their ground falling
     * out of simulation range.
     */
    private void recordKill(@Nonnull final Store<EntityStore> store, @Nonnull final TitanComponent titan) {
        if (siteMemoryType == null || titan.getSiteCell() == TitanComponent.NO_SITE) return;
        store.getResource(siteMemoryType).markCleared(titan.getSiteCell(), KILL_COOLDOWN);
    }

}
