package com.hexvane.titan.system;

import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanLoot;
import com.hexvane.titan.combat.TitanSmashAttack;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the titan state machine, its body movement and its attacks.
 *
 * <p>Runs before {@link TitanAnimationSystem} so the pose is built from the state decided this tick.
 */
public final class TitanAiSystem extends EntityTickingSystem<EntityStore> {

    /** Seconds the arm hangs in the air before it comes down. */
    private static final float WINDUP_SECONDS = 0.7f;
    /** Seconds the arm takes to travel down. */
    private static final float SMASH_SECONDS = 0.55f;
    /** Point in the smash at which the AOE fires. */
    private static final float IMPACT_SECONDS = 0.35f;
    /** Seconds spent pulling the hand back out of the ground. */
    private static final float RECOVER_SECONDS = 0.8f;
    /** Seconds spent reared back before a body slam. Longer than an arm windup: it is a bigger tell. */
    private static final float SLAM_WINDUP_SECONDS = 1.1f;
    /** Seconds the body takes to come down. */
    private static final float SLAM_SECONDS = 0.5f;
    /** Point in the slam at which the AOE fires. */
    private static final float SLAM_IMPACT_SECONDS = 0.3f;
    /** Seconds spent shoving back up off the floor. */
    private static final float RISE_SECONDS = 1.6f;
    /** How far ahead of the root the chest comes down, in blocks. */
    private static final double SLAM_REACH = 3.0;
    /** How far ahead of the root a braced forearm plants, in blocks. */
    private static final double SLAM_HAND_REACH = 5.0;
    /** Sideways spread of the two braced forearms, in blocks. */
    private static final double SLAM_HAND_SPREAD = 4.0;
    /** How fast the body settles onto new terrain height, in blocks per second. */
    private static final double BODY_HEIGHT_FOLLOW = 4.0;
    /** Seconds an idle arm takes to hand control back to the clip pose. */
    private static final float HAND_IK_FADE = 0.4f;
    /** Windup hand height, as a multiple of hip height. */
    private static final double RAISED_HAND_HEIGHT_FACTOR = 1.1;
    /** Seconds the invisible root outlives its body. */
    private static final float DEATH_LINGER_SECONDS = 2f;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanComponent.getComponentType(), TransformComponent.getComponentType());

    @Nonnull
    private final Vector3d scratch = new Vector3d();
    @Nonnull
    private final Vector3d targetPosition = new Vector3d();

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
        if (titan.auditWeakpoints(store)) {
            titan.getVelocity().set(0);
            titan.setState(TitanState.DYING);
            playSound(commandBuffer, variant.getDeathSound(), transform.getPosition());
            return;
        }

        final boolean hasTarget = resolveTarget(store, titan, variant, transform.getPosition());

        switch (titan.getState()) {
            case SLEEPING -> tickSleeping(commandBuffer, titan, variant, transform, hasTarget);
            case WAKING -> tickWaking(titan);
            case IDLE -> tickIdle(titan, hasTarget);
            case CHASE -> tickChase(titan, variant, transform, dt, hasTarget);
            case WINDUP -> tickWindup(titan, variant, transform, dt);
            case SMASH -> tickSmash(store, commandBuffer, self, titan, variant);
            case STUNNED -> tickStunned(titan, variant);
            case RECOVER -> tickRecover(titan, variant);
            case SLAM_WINDUP -> tickSlamWindup(titan, variant, transform, dt);
            case SLAM -> tickSlam(store, commandBuffer, self, titan, variant);
            case PRONE -> tickProne(titan, variant);
            case RISING -> tickRising(titan, variant);
            default -> {
            }
        }

        updateHandGoals(titan, skeleton, dt);
        settleBodyHeight(store, titan, transform, dt);
        transform.getRotation().setYaw(titan.getYaw());
    }

    /**
     * Points the arm IK goals at whatever the current attack needs, and fades every arm the attack is not
     * using back to its clip pose.
     */
    private void updateHandGoals(@Nonnull final TitanComponent titan,
                                 @Nonnull final TitanSkeletonAsset skeleton,
                                 final float dt) {

        if (titan.getState().isBodySlam()) {
            updateBraceGoals(titan, skeleton);
            return;
        }

        final float[] weights = titan.getHandWeights();
        final int active = titan.getState().isArmSmash()
            ? titan.findHandChainForSide(skeleton, titan.getAttackSide())
            : -1;

        for (int i = 0; i < weights.length; i++) {
            if (i != active) {
                weights[i] = Math.max(0f, weights[i] - dt / HAND_IK_FADE);
            }
        }
        if (active < 0) return;

        final double raise = skeleton.getHipHeight() * titan.getScale() * RAISED_HAND_HEIGHT_FACTOR;
        final var goal = titan.getHandGoals()[active];
        final var impact = titan.getAttackPoint();

        switch (titan.getState()) {
            case WINDUP -> {
                goal.set(impact.x, impact.y + raise, impact.z);
                weights[active] = Math.min(1f, titan.getStateTime() / WINDUP_SECONDS);
            }
            case SMASH -> {
                final double t = Math.min(1.0, titan.getStateTime() / IMPACT_SECONDS);
                // Ease in so the arm accelerates into the ground rather than drifting down linearly.
                goal.set(impact.x, impact.y + raise * (1.0 - t * t), impact.z);
                weights[active] = 1f;
            }
            case STUNNED -> {
                goal.set(impact);
                weights[active] = 1f;
            }
            case RECOVER -> {
                final double t = Math.min(1.0, titan.getStateTime() / RECOVER_SECONDS);
                goal.set(impact.x, impact.y + raise * t * 0.5, impact.z);
                weights[active] = (float) (1.0 - t);
            }
            default -> {
            }
        }
    }

    /**
     * Plants both forearms on the floor ahead of the titan through a body slam.
     *
     * <p>The braced arms are what make the slam climbable: the back settles too high to jump onto from flat
     * ground, so the arms have to lie there as a pair of ramps up to it. They are held out through the
     * windup as well, since the titan is already tipping forward onto them by then.
     */
    private void updateBraceGoals(@Nonnull final TitanComponent titan, @Nonnull final TitanSkeletonAsset skeleton) {
        final float[] weights = titan.getHandWeights();
        final var goals = titan.getHandGoals();
        final var chains = skeleton.getIkChains();
        final int[] handChains = titan.getHandChains();

        final double scale = titan.getScale();
        final double reach = SLAM_HAND_REACH * scale;
        final double spread = SLAM_HAND_SPREAD * scale * 0.5;
        final double raise = skeleton.getHipHeight() * scale * RAISED_HAND_HEIGHT_FACTOR;

        // How far off the floor the hands still are. They come down with the body and stay down until it
        // starts to push back up.
        final double lift = switch (titan.getState()) {
            case SLAM_WINDUP -> raise * 0.6 * Math.min(1.0, titan.getStateTime() / SLAM_WINDUP_SECONDS);
            case SLAM -> {
                final double t = Math.min(1.0, titan.getStateTime() / SLAM_IMPACT_SECONDS);
                yield raise * 0.6 * (1.0 - t * t);
            }
            case RISING -> raise * 0.5 * Math.min(1.0, titan.getStateTime() / RISE_SECONDS);
            default -> 0.0;
        };

        final var origin = titan.getAttackPoint();
        final double forwardX = -Math.sin(titan.getYaw());
        final double forwardZ = -Math.cos(titan.getYaw());
        final double rightX = Math.cos(titan.getYaw());
        final double rightZ = -Math.sin(titan.getYaw());

        for (int i = 0; i < weights.length; i++) {
            final double side = Math.signum(chains[handChains[i]].getSide()) * spread;
            goals[i].set(
                origin.x + forwardX * reach + rightX * side,
                origin.y + lift,
                origin.z + forwardZ * reach + rightZ * side
            );
            weights[i] = 1f;
        }
    }

    /**
     * Picks or drops the current target.
     *
     * @return {@code true} when {@link #targetPosition} now holds a live target's position
     */
    private boolean resolveTarget(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final Vector3d position) {

        final Ref<EntityStore> existing = titan.getTarget();
        if (existing != null && existing.isValid()) {
            final var transform = store.getComponent(existing, TransformComponent.getComponentType());
            if (transform != null && transform.getPosition().distance(position) <= variant.getLoseTargetRadius()) {
                targetPosition.set(transform.getPosition());
                return true;
            }
        }

        final double searchRadius = titan.getState() == TitanState.SLEEPING
            ? variant.getWakeRadius()
            : variant.getLoseTargetRadius();

        final Ref<EntityStore> found = findNearestPlayer(store, position, searchRadius);
        titan.setTarget(found);
        if (found == null) return false;

        final var transform = store.getComponent(found, TransformComponent.getComponentType());
        if (transform == null) return false;
        targetPosition.set(transform.getPosition());
        return true;
    }

    @Nullable
    private Ref<EntityStore> findNearestPlayer(@Nonnull final Store<EntityStore> store,
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
        if (!hasTarget) return;
        if (targetPosition.distance(transform.getPosition()) > variant.getWakeRadius()) return;

        titan.setState(TitanState.WAKING);
        playSound(commandBuffer, variant.getWakeSound(), transform.getPosition());
    }

    private static void playSound(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nullable final String sound,
                                  @Nonnull final Vector3d position) {
        if (sound == null || sound.isEmpty()) return;
        final int soundIndex = SoundEvent.getAssetMap().getIndex(sound);
        if (soundIndex == SoundEvent.EMPTY_ID) return;
        SoundUtil.playSoundEvent3d(null, soundIndex, position.x, position.y, position.z, commandBuffer);
    }

    private void tickWaking(@Nonnull final TitanComponent titan) {
        final var animator = titan.getAnimator();
        if (animator == null || animator.isFinished()) {
            titan.setState(TitanState.IDLE);
        }
    }

    private void tickIdle(@Nonnull final TitanComponent titan, final boolean hasTarget) {
        titan.getVelocity().set(0);
        if (hasTarget) titan.setState(TitanState.CHASE);
    }

    private void tickChase(@Nonnull final TitanComponent titan,
                           @Nonnull final TitanVariantAsset variant,
                           @Nonnull final TransformComponent transform,
                           final float dt,
                           final boolean hasTarget) {
        if (!hasTarget) {
            titan.getVelocity().set(0);
            titan.setState(TitanState.IDLE);
            return;
        }

        final var position = transform.getPosition();
        turnTowards(titan, position, targetPosition, variant.getTurnSpeed(), dt);

        final double distance = horizontalDistance(position, targetPosition);
        if (distance <= variant.getAttackRange() && titan.getAttackCooldown() <= 0f) {
            if (ThreadLocalRandom.current().nextFloat() < variant.getSlamChance()) {
                titan.setState(TitanState.SLAM_WINDUP);
            } else {
                titan.setAttackSide(chooseAttackSide(titan, position));
                titan.setState(TitanState.WINDUP);
            }
            titan.getVelocity().set(0);
            return;
        }

        // Only close in once roughly facing the target, so the whole body does not crab sideways.
        final double facing = Math.cos(angleTo(position, targetPosition) - titan.getYaw());
        if (facing < 0.7 || distance <= variant.getAttackRange()) {
            titan.getVelocity().set(0);
            return;
        }

        final double step = variant.getMoveSpeed() * dt;
        scratch.set(-Math.sin(titan.getYaw()), 0, -Math.cos(titan.getYaw()));
        titan.getVelocity().set(scratch).mul(variant.getMoveSpeed());
        position.fma(step, scratch);
    }

    private void tickWindup(@Nonnull final TitanComponent titan,
                            @Nonnull final TitanVariantAsset variant,
                            @Nonnull final TransformComponent transform,
                            final float dt) {
        turnTowards(titan, transform.getPosition(), targetPosition, variant.getTurnSpeed() * 1.5f, dt);
        if (titan.getStateTime() >= WINDUP_SECONDS) {
            TitanSmashAttack.resolveImpactPoint(
                transform.getPosition(),
                titan.getTarget() != null ? targetPosition : null,
                titan.getYaw(),
                variant.getAttackRange(),
                titan.getAttackPoint());
            titan.setState(TitanState.SMASH);
        }
    }

    private void tickSmash(@Nonnull final Store<EntityStore> store,
                           @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                           @Nonnull final Ref<EntityStore> self,
                           @Nonnull final TitanComponent titan,
                           @Nonnull final TitanVariantAsset variant) {

        if (!titan.isImpactFired() && titan.getStateTime() >= IMPACT_SECONDS) {
            titan.setImpactFired(true);
            TitanSmashAttack.execute(store, commandBuffer, self, variant, titan.getAttackPoint());
        }

        if (titan.getStateTime() >= SMASH_SECONDS) {
            titan.setState(TitanState.STUNNED);
        }
    }

    private void tickStunned(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= variant.getStunSeconds()) {
            titan.setState(TitanState.RECOVER);
        }
    }

    /**
     * Rears back, then throws the whole body forward onto the floor.
     *
     * <p>The impact point is fixed here rather than tracked through the drop, so the slam is dodgeable in
     * the same way the arm smash is: commit to a spot, and the target has the fall to get out of it.
     */
    private void tickSlamWindup(@Nonnull final TitanComponent titan,
                                @Nonnull final TitanVariantAsset variant,
                                @Nonnull final TransformComponent transform,
                                final float dt) {
        turnTowards(titan, transform.getPosition(), targetPosition, variant.getTurnSpeed() * 1.5f, dt);

        // Lands under the chest rather than on the target: the titan is falling on its own front, and
        // aiming the blast at the player would let it belly-flop sideways onto someone stood beside it.
        // Tracked through the windup so the braced arms follow the turn, and frozen once SLAM begins.
        TitanSmashAttack.resolveImpactPoint(
            transform.getPosition(), null, titan.getYaw(), SLAM_REACH, titan.getAttackPoint());

        if (titan.getStateTime() >= SLAM_WINDUP_SECONDS) {
            titan.setState(TitanState.SLAM);
        }
    }

    private void tickSlam(@Nonnull final Store<EntityStore> store,
                          @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                          @Nonnull final Ref<EntityStore> self,
                          @Nonnull final TitanComponent titan,
                          @Nonnull final TitanVariantAsset variant) {

        if (!titan.isImpactFired() && titan.getStateTime() >= SLAM_IMPACT_SECONDS) {
            titan.setImpactFired(true);
            TitanSmashAttack.execute(store, commandBuffer, self, variant, titan.getAttackPoint(),
                variant.getSlamRadius());
        }

        if (titan.getStateTime() >= SLAM_SECONDS) {
            titan.setState(TitanState.PRONE);
        }
    }

    private void tickProne(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= variant.getSlamProneSeconds()) {
            titan.setState(TitanState.RISING);
        }
    }

    private void tickRising(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= RISE_SECONDS) {
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.CHASE);
        }
    }

    /**
     * Winds the boss down. The voxels have already been kicked loose by {@link TitanPartSyncSystem}, so the
     * root only has to spill the loot and then get out of the way.
     */
    private void tickDying(final float dt,
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

    private void tickRecover(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= RECOVER_SECONDS) {
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.CHASE);
        }
    }

    /** Alternates arms, preferring the one the target is standing on the side of. */
    private int chooseAttackSide(@Nonnull final TitanComponent titan, @Nonnull final Vector3d position) {
        final double dx = targetPosition.x - position.x;
        final double dz = targetPosition.z - position.z;
        // Right vector at this yaw, used to work out which side of the body the target is on.
        final double rightX = Math.cos(titan.getYaw());
        final double rightZ = -Math.sin(titan.getYaw());
        final double side = dx * rightX + dz * rightZ;
        if (Math.abs(side) < 0.5) return -titan.getAttackSide();
        return side >= 0 ? 1 : -1;
    }

    private void turnTowards(@Nonnull final TitanComponent titan,
                             @Nonnull final Vector3d from,
                             @Nonnull final Vector3d to,
                             final float turnSpeed,
                             final float dt) {
        final float desired = angleTo(from, to);
        final float delta = MathUtil.wrapAngle(desired - titan.getYaw());
        final float maxStep = turnSpeed * dt;
        titan.setYaw(MathUtil.wrapAngle(titan.getYaw() + MathUtil.clamp(delta, -maxStep, maxStep)));
    }

    private static float angleTo(@Nonnull final Vector3d from, @Nonnull final Vector3d to) {
        return (float) Math.atan2(-(to.x - from.x), -(to.z - from.z));
    }

    private static double horizontalDistance(@Nonnull final Vector3d a, @Nonnull final Vector3d b) {
        final double dx = a.x - b.x;
        final double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Eases the root towards the terrain under it. The root sits at the feet plane, so the body bone's own
     * bind offset provides the hip height.
     */
    private void settleBodyHeight(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TransformComponent transform,
                                  final float dt) {
        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        final var position = transform.getPosition();
        final double ground = GroundSampler.sample(chunkStore, position.x, position.y, position.z, 6, 16);
        if (!GroundSampler.isValid(ground)) return;

        final double delta = ground - position.y;
        final double maxStep = BODY_HEIGHT_FOLLOW * dt;
        position.y += Math.abs(delta) <= maxStep ? delta : Math.copySign(maxStep, delta);
    }
}
