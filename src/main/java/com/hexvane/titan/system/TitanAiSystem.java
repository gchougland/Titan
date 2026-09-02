package com.hexvane.titan.system;

import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanBoulder;
import com.hexvane.titan.combat.TitanLoot;
import com.hexvane.titan.combat.TitanRider;
import com.hexvane.titan.combat.TitanSmashAttack;
import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.combat.TitanTelegraph;
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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hexvane.titan.spawn.TitanEnvironment;
import com.hexvane.titan.spawn.TitanSiteMemory;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the titan state machine, its body movement and its attacks.
 *
 * <p>Runs before {@link TitanAnimationSystem} so the pose is built from the state decided this tick.
 */
public final class TitanAiSystem extends EntityTickingSystem<EntityStore> {

    /**
     * Seconds the arm hangs in the air before it comes down.
     *
     * <p>Long enough for the marker under it to be seen and stepped out of. The arm smash is the attack a
     * titan throws most often, so if any windup is going to be too short to read this is the one, and it is
     * held in the same second-or-so band as the rest for that reason rather than because the animation
     * needs the time.
     */
    private static final float WINDUP_SECONDS = 1.1f;
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
    /** How long a titan stays angry after being hit, holding its target out to the full leash range. */
    private static final float PROVOKED_SECONDS = 20f;
    /** How close to its spawn spot counts as home, in blocks. Roughly the width of the titan itself. */
    private static final double HOME_ARRIVAL_RADIUS = 3.0;

    /** How close to a wander goal counts as having arrived, in blocks. */
    private static final double WANDER_ARRIVAL_RADIUS = 4.0;
    /** Shortest wander leg worth setting off on, in blocks. Below this the titan just shuffles in place. */
    private static final double WANDER_MIN_LEG = 12.0;
    /** Rolls per tick for somewhere to wander to before giving up and trying again next tick. */
    private static final int WANDER_GOAL_ATTEMPTS = 6;
    /**
     * Vertical search window for the ground under a candidate wander goal, in blocks.
     *
     * <p>The upward reach is deliberately short. The scan takes the first solid block from the top down,
     * and a tree counts as solid, so reaching high enough to clear a canopy means a goal in a wood lands
     * in the branches and the titan sets off towards a point fifteen blocks above the dirt. Eight blocks
     * is more than enough for terrain that rises inside a wander radius and too little to catch a tree.
     */
    private static final int WANDER_GROUND_ABOVE = 8;
    private static final int WANDER_GROUND_BELOW = 32;

    /** Seconds spent hauling both fists overhead. */
    private static final float POUND_WINDUP_SECONDS = 0.95f;
    /** Seconds both fists take to come down. */
    private static final float POUND_SECONDS = 0.5f;
    /** Point in the pound at which the launch fires. */
    private static final float POUND_IMPACT_SECONDS = 0.3f;
    /** Seconds spent dragging both arms back out of the ground. */
    private static final float POUND_RECOVER_SECONDS = 1.1f;
    /** How far ahead of the root both fists land, in blocks. */
    private static final double POUND_REACH = 3.5;
    /** How far apart the two fists land, in blocks. */
    private static final double POUND_FIST_SPREAD = 3.0;
    /**
     * How much of a pound's throw goes straight up.
     *
     * <p>Nearly all of it, which is what makes this a different attack rather than a wider smash. You are
     * not knocked away from a pound, you are put twenty blocks in the air above where you were standing, and
     * what hurts is the ground on the way back down.
     */
    private static final double POUND_VERTICAL_SHARE = 0.9;

    /** Seconds spent digging a boulder out of the ground. */
    private static final float HURL_WINDUP_SECONDS = 1.3f;
    /** Point in the windup at which the rock actually comes free. */
    private static final float HURL_RIP_SECONDS = 0.8f;
    /** Seconds the throw itself takes. */
    private static final float HURL_SECONDS = 0.6f;
    /** Point in the throw at which the rock leaves the hand. */
    private static final float HURL_RELEASE_SECONDS = 0.25f;
    /** Seconds spent following through afterwards. Short: the titan never left its feet. */
    private static final float HURL_RECOVER_SECONDS = 0.5f;
    /** How far ahead of the root the titan digs, in blocks. */
    private static final double HURL_RIP_REACH = 4.0;
    /**
     * How far ahead of and above the root the rock leaves from, in blocks.
     *
     * <p>The height is chest rather than overhead. It is added to whatever the arc itself climbs, so a
     * release point up at the titan's full standing height starts the rock most of the way to the treetops
     * before it has travelled at all.
     */
    private static final double HURL_RELEASE_REACH = 5.0;
    private static final double HURL_RELEASE_HEIGHT = 5.5;

    /** Seconds spent rearing up and pitching the front down before a plough. */
    private static final float PLOW_WINDUP_SECONDS = 1.0f;
    /** How often a plough in progress shovels whatever is in front of it. */
    private static final float PLOW_SWEEP_SECONDS = 0.3f;
    /** How far ahead of the root the plough's blade is, in blocks. */
    private static final double PLOW_BLADE_REACH = 4.0;
    /** How far the corridor marker reaches ahead of the titan, as a multiple of the run it will make. */
    private static final double PLOW_TELEGRAPH_LEAD = 1.15;
    /** How much of the throw given to a rider goes straight up, against backwards. */
    private static final double PLOW_RIDER_LIFT = 0.55;
    /** How far around the body to look for riders, in blocks. Comfortably wider than a titan. */
    private static final double RIDER_SEARCH_RADIUS = 16.0;

    /** Fraction of a windup still to run when the danger circle starts filling in. */
    private static final float TELEGRAPH_FILL_LEAD = 0.45f;
    /**
     * Fraction of a windup spent still aiming. Past it the spot is fixed and the marker stops moving.
     *
     * <p>Without this the attack is aimed at wherever the target is on the last tick before it lands, which
     * makes the marker an accurate report of something you cannot avoid: it follows you until it hits you.
     * The first part of the windup is the titan choosing, and the rest is the window to be somewhere else.
     */
    private static final float AIM_COMMIT = 0.4f;
    /** Relative weight of the ordinary arm smash, which every other melee chance is measured against. */
    private static final float SMASH_WEIGHT = 1f;

    /** How long a natural spawn site stays empty after its titan is killed, in seconds. */
    private static final float KILL_COOLDOWN = 900f;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanComponent.getComponentType(), TransformComponent.getComponentType());

    /** Where a kill is written down, so the site stays empty across a restart. */
    @Nullable
    private final ResourceType<EntityStore, TitanSiteMemory> siteMemoryType;

    public TitanAiSystem(@Nullable final ResourceType<EntityStore, TitanSiteMemory> siteMemoryType) {
        this.siteMemoryType = siteMemoryType;
    }

    @Nonnull
    private final Vector3d scratch = new Vector3d();
    @Nonnull
    private final Vector3d targetPosition = new Vector3d();
    @Nonnull
    private final Vector3d scratchPoint = new Vector3d();
    @Nonnull
    private final Vector3d impulse = new Vector3d();
    /** Reused by the rider search, which runs at most once per titan per tick. */
    @Nonnull
    private final List<Ref<EntityStore>> riders = new ArrayList<>();

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
        switch (titan.auditWeakpoints(store, dt)) {
            case DESTROYED -> {
                titan.getVelocity().set(0);
                titan.setState(TitanState.DYING);
                TitanSound.play(commandBuffer, variant.getDeathSound(), transform.getPosition());
                recordKill(store, titan);
                return;
            }
            case LOST -> {
                // Part of the rig has gone out from under it, so this is an unload rather than a kill.
                // Leaving quietly matters: dying here would drop a pile of ore at a site no player has
                // touched and mark the site cleared for the next quarter of an hour.
                commandBuffer.removeEntity(self, RemoveReason.REMOVE);
                return;
            }
            case INTACT -> {
            }
        }

        titan.tickProvoked(dt);
        retaliate(commandBuffer, titan, variant, transform);

        final boolean hasTarget = resolveTarget(store, titan, variant, transform.getPosition());

        switch (titan.getState()) {
            case SLEEPING -> tickSleeping(commandBuffer, titan, variant, transform, hasTarget);
            case WAKING -> tickWaking(titan);
            case IDLE -> tickIdle(store, titan, variant, transform, dt, hasTarget);
            case CHASE -> tickChase(store, commandBuffer, titan, variant, skeleton, transform, dt, hasTarget);
            case WINDUP -> tickWindup(store, commandBuffer, titan, variant, transform, dt);
            case SMASH -> tickSmash(store, commandBuffer, self, titan, variant);
            case STUNNED -> tickStunned(titan, variant);
            case RECOVER -> tickRecover(titan, variant);
            case SLAM_WINDUP -> tickSlamWindup(store, commandBuffer, titan, variant, transform, dt);
            case SLAM -> tickSlam(store, commandBuffer, self, titan, variant);
            case PRONE -> tickProne(titan, variant);
            case RISING -> tickRising(titan, variant);
            case POUND_WINDUP -> tickPoundWindup(store, commandBuffer, titan, variant, transform, dt);
            case POUND -> tickPound(store, commandBuffer, self, titan, variant);
            case POUND_STUNNED -> tickPoundStunned(titan, variant);
            case POUND_RECOVER -> tickPoundRecover(titan, variant);
            case HURL_WINDUP -> tickHurlWindup(store, commandBuffer, titan, variant, transform, dt);
            case HURL -> tickHurl(store, commandBuffer, self, titan, variant, skeleton, transform);
            case HURL_RECOVER -> tickHurlRecover(titan, variant);
            case PLOW_WINDUP -> tickPlowWindup(store, commandBuffer, titan, variant, transform, dt);
            case PLOW -> tickPlow(store, commandBuffer, self, titan, variant, skeleton, transform, dt);
            case PLOW_RECOVER -> tickPlowRecover(titan, variant, transform);
            case STOMP_WINDUP -> tickStompWindup(store, commandBuffer, titan, variant, transform, dt);
            case STOMP -> tickStomp(store, commandBuffer, self, titan, variant);
            case STOMP_RECOVER -> tickStompRecover(titan, variant);
            case EMOTING -> titan.getVelocity().set(0);
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
        if (titan.getState().isGroundPound()) {
            updatePoundGoals(titan, skeleton);
            return;
        }
        if (titan.getState().isHeadPlow()) {
            updatePlowGoals(titan, skeleton);
            return;
        }

        final float[] weights = titan.getHandWeights();
        final int active = titan.getState().isOneArmed()
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
            case HURL_WINDUP -> {
                // Down to the ground, grip, then haul back up. The turn at the rip point is the moment the
                // rock comes free, and it is the same moment the ground is shown splitting.
                final double t = titan.getStateTime();
                final double lift = t < HURL_RIP_SECONDS
                    ? raise * (1.0 - t / HURL_RIP_SECONDS)
                    : raise * 0.8 * ((t - HURL_RIP_SECONDS) / Math.max(1.0e-3, HURL_WINDUP_SECONDS - HURL_RIP_SECONDS));
                goal.set(impact.x, impact.y + Math.max(0.0, lift), impact.z);
                weights[active] = Math.min(1f, (float) (t / (HURL_RIP_SECONDS * 0.5)));
            }
            case HURL -> {
                // Sweeps up and forward past the release point, so the rock is already gone by the time the
                // arm reaches the end of its travel.
                final double t = Math.min(1.0, titan.getStateTime() / HURL_SECONDS);
                final double scale = titan.getScale();
                goal.set(
                    impact.x - Math.sin(titan.getYaw()) * HURL_RELEASE_REACH * scale * t,
                    impact.y + raise * 0.8 + HURL_RELEASE_HEIGHT * scale * t * 0.5,
                    impact.z - Math.cos(titan.getYaw()) * HURL_RELEASE_REACH * scale * t
                );
                weights[active] = 1f;
            }
            case HURL_RECOVER ->
                weights[active] = Math.max(0f, 1f - titan.getStateTime() / HURL_RECOVER_SECONDS);
            default -> {
            }
        }
    }

    /**
     * Drives both fists at a pair of points either side of the impact.
     *
     * <p>The two-handed cousin of the arm smash, and the reason the pound leaves the widest opening in the
     * fight: where a smash buries one arm and leaves one ramp onto the back, this buries both.
     */
    private void updatePoundGoals(@Nonnull final TitanComponent titan, @Nonnull final TitanSkeletonAsset skeleton) {
        final float[] weights = titan.getHandWeights();
        final var goals = titan.getHandGoals();
        final var chains = skeleton.getIkChains();
        final int[] handChains = titan.getHandChains();

        final double scale = titan.getScale();
        final double raise = skeleton.getHipHeight() * scale * RAISED_HAND_HEIGHT_FACTOR;
        final double spread = POUND_FIST_SPREAD * scale * 0.5;

        final double lift = switch (titan.getState()) {
            case POUND_WINDUP -> raise * 1.2 * Math.min(1.0, titan.getStateTime() / POUND_WINDUP_SECONDS);
            case POUND -> {
                final double t = Math.min(1.0, titan.getStateTime() / POUND_IMPACT_SECONDS);
                yield raise * 1.2 * (1.0 - t * t);
            }
            case POUND_RECOVER -> raise * Math.min(1.0, titan.getStateTime() / POUND_RECOVER_SECONDS);
            default -> 0.0;
        };

        final var impact = titan.getAttackPoint();
        final double rightX = Math.cos(titan.getYaw());
        final double rightZ = -Math.sin(titan.getYaw());

        for (int i = 0; i < weights.length; i++) {
            final double side = Math.signum(chains[handChains[i]].getSide()) * spread;
            goals[i].set(impact.x + rightX * side, impact.y + lift, impact.z + rightZ * side);
            weights[i] = titan.getState() == TitanState.POUND_RECOVER
                ? (float) Math.max(0.0, 1.0 - titan.getStateTime() / POUND_RECOVER_SECONDS)
                : 1f;
        }
    }

    /**
     * Sweeps both arms back and out of the way of the plough.
     *
     * <p>They have to go somewhere, and behind is the only place they can be: out in front they would be
     * ploughing instead of the body, and at the sides they would sit in the corridor the attack is meant to
     * clear. Swept back they also read as what they are, a pair of legs pushing.
     */
    private void updatePlowGoals(@Nonnull final TitanComponent titan, @Nonnull final TitanSkeletonAsset skeleton) {
        final float[] weights = titan.getHandWeights();
        final var goals = titan.getHandGoals();
        final var chains = skeleton.getIkChains();
        final int[] handChains = titan.getHandChains();

        final double scale = titan.getScale();
        final double reach = SLAM_HAND_REACH * scale;
        final double spread = SLAM_HAND_SPREAD * scale * 0.5;
        final double height = skeleton.getHipHeight() * scale * 0.35;

        final double blend = titan.getState() == TitanState.PLOW_WINDUP
            ? Math.min(1.0, titan.getStateTime() / PLOW_WINDUP_SECONDS)
            : 1.0;

        final var origin = titan.getAttackPoint();
        // Backwards, hence the sign flip against the forward vector every other attack uses.
        final double backX = Math.sin(titan.getYaw());
        final double backZ = Math.cos(titan.getYaw());
        final double rightX = Math.cos(titan.getYaw());
        final double rightZ = -Math.sin(titan.getYaw());

        for (int i = 0; i < weights.length; i++) {
            final double side = Math.signum(chains[handChains[i]].getSide()) * spread;
            goals[i].set(
                origin.x + backX * reach * blend + rightX * side,
                origin.y + height,
                origin.z + backZ * reach * blend + rightZ * side
            );
            weights[i] = (float) blend;
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
     * Turns a hit on an ore node into a target.
     *
     * <p>A titan has no health of its own, so the only thing that ever tells it that it is under attack is
     * one of its nodes taking damage. Without this you could stand off and pick a sleeping one apart.
     */
    private void retaliate(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
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
     * @return {@code true} when {@link #targetPosition} now holds a live target's position
     */
    private boolean resolveTarget(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final Vector3d position) {

        // A passive titan is not looking for anybody. Being stood next to is not a provocation, so it will
        // be walked under and climbed on all day; the only thing that gives it a target is being hit, and
        // it holds that target for exactly as long as the grudge lasts.
        if (variant.isPassive() && !titan.isProvoked()) {
            titan.setTarget(null);
            return false;
        }

        // Freshly hit, the titan holds on out to the edge of the ground it defends instead of the much
        // shorter distance it normally loses interest at, so an archer cannot tag it and be forgotten
        // before it has finished standing up.
        final double keepRadius = titan.isProvoked()
            ? Math.max(variant.getLoseTargetRadius(), variant.getLeashRadius())
            : variant.getLoseTargetRadius();

        final Ref<EntityStore> existing = titan.getTarget();
        if (existing != null && existing.isValid()) {
            final var transform = store.getComponent(existing, TransformComponent.getComponentType());
            if (transform != null
                && transform.getPosition().distance(position) <= keepRadius
                && isWithinLeash(titan, variant, transform.getPosition())) {
                targetPosition.set(transform.getPosition());
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
        targetPosition.set(transform.getPosition());
        return true;
    }

    /**
     * Whether a point is on the ground this titan defends.
     *
     * <p>The leash is measured from where the titan was built rather than from where it currently stands,
     * so a long chase cannot walk it a step at a time across the map. Run far enough and it stops caring;
     * {@link #tickIdle} then brings it home.
     */
    private static boolean isWithinLeash(@Nonnull final TitanComponent titan,
                                         @Nonnull final TitanVariantAsset variant,
                                         @Nonnull final Vector3d point) {
        final double leash = variant.getLeashRadius();
        if (leash <= 0) return true;
        return horizontalDistance(titan.getHome(), point) <= leash;
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
        TitanSound.play(commandBuffer, variant.getWakeSound(), transform.getPosition());
    }

    private void tickWaking(@Nonnull final TitanComponent titan) {
        final var animator = titan.getAnimator();
        if (animator == null || animator.isFinished()) {
            titan.setState(TitanState.IDLE);
        }
    }

    /**
     * Nothing to fight. Walks back to the spawn spot if the last chase left it out of place, then stands
     * there as a boulder-shaped landmark again until somebody comes near.
     *
     * <p>A variant with a wander radius does not stand still: it drifts between points inside that circle
     * with a pause at each one. See {@link #tickWander}.
     */
    private void tickIdle(@Nonnull final Store<EntityStore> store,
                          @Nonnull final TitanComponent titan,
                          @Nonnull final TitanVariantAsset variant,
                          @Nonnull final TransformComponent transform,
                          final float dt,
                          final boolean hasTarget) {
        if (hasTarget) {
            titan.getVelocity().set(0);
            titan.setState(TitanState.CHASE);
            return;
        }

        final var position = transform.getPosition();

        if (variant.getWanderRadius() > 0) {
            tickWander(store, titan, variant, position, dt);
            return;
        }

        if (horizontalDistance(position, titan.getHome()) <= HOME_ARRIVAL_RADIUS) {
            titan.getVelocity().set(0);
            return;
        }
        walkTowards(titan, variant, position, titan.getHome(), HOME_ARRIVAL_RADIUS, dt);
    }

    /**
     * Drifts between points inside the wander circle, standing a while at each.
     *
     * <p>The pause is most of what makes it read as wandering rather than patrolling: something this size
     * moving continuously looks like it is going somewhere, and it is not. A leg that fails its checks
     * costs a tick and is rolled again on the next one, so a titan boxed in on three sides by unsuitable
     * ground simply stands there until a roll finds the way out.
     */
    private void tickWander(@Nonnull final Store<EntityStore> store,
                            @Nonnull final TitanComponent titan,
                            @Nonnull final TitanVariantAsset variant,
                            @Nonnull final Vector3d position,
                            final float dt) {

        if (titan.isWandering()) {
            if (horizontalDistance(position, titan.getWanderGoal()) > WANDER_ARRIVAL_RADIUS) {
                walkTowards(titan, variant, position, titan.getWanderGoal(), WANDER_ARRIVAL_RADIUS, dt);
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
     * Picks somewhere inside the wander circle to walk to next, and says whether it found one.
     *
     * <p>Three things disqualify a candidate. It has to be far enough away to be worth walking to, or the
     * titan spends its life shuffling. The ground under it has to be loaded, or it is being sent at a part
     * of the world that does not exist yet. And it has to be in one of the variant's environments, which
     * is what keeps a plains titan out of the forest.
     */
    private boolean rollWanderGoal(@Nonnull final Store<EntityStore> store,
                                   @Nonnull final TitanComponent titan,
                                   @Nonnull final TitanVariantAsset variant,
                                   @Nonnull final Vector3d position) {

        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        final var random = ThreadLocalRandom.current();
        final double radius = variant.getWanderRadius();

        // A fence only holds somebody who is inside it. One of these built outside the biome it belongs to,
        // which is what a command spawn usually means, would otherwise fail every roll it ever made and
        // stand rooted to the spot, and a titan that does not move is a much worse answer than one briefly
        // in the wrong forest.
        final boolean fenced = TitanEnvironment.matches(chunkStore, variant.getEnvironments(), position);

        for (int attempt = 0; attempt < WANDER_GOAL_ATTEMPTS; attempt++) {
            // Square-rooted so the points are spread evenly over the disc rather than bunched at the
            // middle, which is what a titan that mostly stands near its spawn would look like.
            final double distance = radius * Math.sqrt(random.nextDouble());
            final double angle = random.nextDouble() * Math.PI * 2;
            scratchPoint.set(
                titan.getHome().x + Math.cos(angle) * distance,
                titan.getHome().y,
                titan.getHome().z + Math.sin(angle) * distance
            );

            if (horizontalDistance(position, scratchPoint) < WANDER_MIN_LEG) continue;

            final double ground = GroundSampler.sample(
                chunkStore, scratchPoint.x, scratchPoint.y, scratchPoint.z, WANDER_GROUND_ABOVE, WANDER_GROUND_BELOW);
            if (!GroundSampler.isValid(ground)) continue;
            scratchPoint.y = ground;

            if (fenced && !TitanEnvironment.matches(chunkStore, variant.getEnvironments(), scratchPoint)) continue;

            titan.getWanderGoal().set(scratchPoint);
            return true;
        }
        return false;
    }

    /**
     * Walks towards the target, and decides what to do when it gets there.
     *
     * <p>Three questions in order. Is somebody riding the back, in which case the plough is the answer and
     * nothing else will do? Is the target close enough to hit, in which case pick between the three melee
     * attacks? Or is it out of reach but within throwing distance, in which case throw a rock at it rather
     * than trudging over — which is also what stops a titan following an archer around forever.
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

        if (titan.getAttackCooldown() <= 0f) {
            if (tryPlow(store, commandBuffer, titan, variant, skeleton, transform)) return;

            final double distance = horizontalDistance(position, targetPosition);

            if (distance <= variant.getAttackRange()) {
                turnTowards(titan, position, targetPosition, variant.getTurnSpeed(), dt);
                beginMelee(commandBuffer, titan, variant, position);
                titan.getVelocity().set(0);
                return;
            }

            if (distance >= variant.getHurlMinRange() && distance <= variant.getHurlMaxRange()
                && ThreadLocalRandom.current().nextFloat() < variant.getHurlChance()) {
                turnTowards(titan, position, targetPosition, variant.getTurnSpeed(), dt);
                titan.setAttackSide(chooseAttackSide(titan, position));
                titan.setState(TitanState.HURL_WINDUP);
                TitanSound.play(commandBuffer, variant.getTelegraphSound(), position);
                titan.getVelocity().set(0);
                return;
            }
        }

        // A titan that does not give chase has already had its answer to a target in range. Out of range it
        // holds its ground and turns to watch instead of following, so walking away from one really is an
        // escape. It stays in CHASE rather than dropping to IDLE because the target is still live: bouncing
        // back to IDLE would be undone by the next tick, and would restart the clip on every one of them.
        if (!variant.isChase()) {
            turnTowards(titan, position, targetPosition, variant.getTurnSpeed(), dt);
            titan.getVelocity().set(0);
            return;
        }

        walkTowards(titan, variant, position, targetPosition, variant.getAttackRange(), dt);
    }

    /**
     * Chooses between the melee attacks by weight.
     *
     * <p>The chances in the variant are read as weights against each other rather than as probabilities, so
     * raising one does not silently take from the other and a variant can be given, say, twice as many
     * pounds as smashes by writing two. Zeroing one takes it out of the rotation, which is how a titan with
     * no arms is left with nothing but its legs to answer with.
     *
     * <p>A variant that has zeroed everything gets the arm smash anyway. That is a misconfiguration rather
     * than a request to stand there, and a titan that will not attack is much harder to notice as a bug
     * than one swinging an arm it does not have.
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
            beginStomp(titan);
        } else {
            titan.setAttackSide(chooseAttackSide(titan, position));
            titan.setState(TitanState.WINDUP);
        }
        TitanSound.play(commandBuffer, variant.getTelegraphSound(), position);
    }

    /**
     * Commits the leg nearest the target to a stomp.
     *
     * <p>Chosen by where the feet are standing rather than by which corner of the body they hang off, so a
     * titan caught mid-stride uses the leg that is genuinely closest. The choice is made once, here, and
     * held for the rest of the attack: re-picking during the windup would let a player walking around it
     * drag the raised leg after them, and the whole point of the telegraph is that the spot is committed.
     */
    private void beginStomp(@Nonnull final TitanComponent titan) {
        final int foot = titan.findFootNearest(targetPosition);
        if (foot < 0) {
            titan.setState(TitanState.WINDUP);
            return;
        }

        // Fixed here rather than read back each tick, because the contact point stops being available the
        // moment the leg leaves the ground. Taken from where the foot is heading if it was caught in the
        // middle of a step, since its current position is somewhere up in the arc.
        final var state = titan.getFeet()[foot];
        titan.getAttackPoint().set(state.stepping ? state.stepTarget : state.planted);

        titan.setStompFoot(foot);
        titan.getStompGoal().set(state.current);
        titan.setState(TitanState.STOMP_WINDUP);
    }

    /**
     * Starts a plough if somebody is on the back, the cooldown has run out, and the roll goes its way.
     *
     * <p>All three gates matter. Without the roll, climbing on would be answered instantly every single
     * time and there would be no point trying; without the separate cooldown, the fact that being climbed
     * means being hit — which keeps the ordinary attack cooldown at zero — would have the titan ploughing
     * over and over and nobody would ever reach the ore.
     *
     * @return whether a plough was started
     */
    private boolean tryPlow(@Nonnull final Store<EntityStore> store,
                            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                            @Nonnull final TitanComponent titan,
                            @Nonnull final TitanVariantAsset variant,
                            @Nonnull final TitanSkeletonAsset skeleton,
                            @Nonnull final TransformComponent transform) {

        if (titan.getPlowCooldown() > 0f || variant.getPlowChance() <= 0f) return false;

        final var pose = titan.getPose();
        if (pose == null) return false;

        final TitanRider.Back back = TitanRider.measure(skeleton);
        if (back == null) return false;

        if (!TitanRider.any(store, pose, back, transform.getPosition(), RIDER_SEARCH_RADIUS * titan.getScale(), riders)) {
            return false;
        }
        if (ThreadLocalRandom.current().nextFloat() >= variant.getPlowChance()) {
            // Rolled and lost. The cooldown still starts, so the next roll is a while away rather than on
            // the very next tick, which would turn the chance into a formality.
            titan.setPlowCooldown(variant.getPlowCooldown() * 0.5f);
            return false;
        }

        titan.getVelocity().set(0);
        titan.setState(TitanState.PLOW_WINDUP);
        TitanSound.play(commandBuffer, variant.getTelegraphSound(), transform.getPosition());
        return true;
    }

    /** Whether a windup has passed the point where it stops aiming. See {@link #AIM_COMMIT}. */
    private static boolean hasCommitted(@Nonnull final TitanComponent titan, final float windupSeconds) {
        return titan.getStateTime() >= windupSeconds * AIM_COMMIT;
    }

    /**
     * Marks the ground a windup is aimed at, beating faster as the attack nears.
     *
     * @param remaining seconds of windup left
     */
    private void telegraphCircle(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull final TitanComponent titan,
                                 @Nonnull final TitanVariantAsset variant,
                                 @Nonnull final Vector3d centre,
                                 final double radius,
                                 final float remaining,
                                 final float dt) {

        if (!titan.consumePulse(dt, TitanTelegraph.pulseInterval(remaining))) return;

        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        TitanTelegraph.ring(commandBuffer, chunkStore, variant.getTelegraphRingParticle(), centre, radius, titan.getYaw());

        // The disc only appears at the end, so it reads as the circle closing rather than as decoration
        // that was there the whole time.
        if (remaining <= TELEGRAPH_FILL_LEAD) {
            TitanTelegraph.ring(commandBuffer, chunkStore, variant.getTelegraphFillParticle(), centre, radius, titan.getYaw());
        }
    }

    /**
     * Turns towards a point and, once roughly facing it, walks. Holding still until it has come round is
     * what stops the whole body crabbing sideways.
     */
    private void walkTowards(@Nonnull final TitanComponent titan,
                             @Nonnull final TitanVariantAsset variant,
                             @Nonnull final Vector3d position,
                             @Nonnull final Vector3d goal,
                             final double arrivalRadius,
                             final float dt) {

        turnTowards(titan, position, goal, variant.getTurnSpeed(), dt);

        final double facing = Math.cos(angleTo(position, goal) - titan.getYaw());
        if (facing < 0.7 || horizontalDistance(position, goal) <= arrivalRadius) {
            titan.getVelocity().set(0);
            return;
        }

        scratch.set(-Math.sin(titan.getYaw()), 0, -Math.cos(titan.getYaw()));
        titan.getVelocity().set(scratch).mul(variant.getMoveSpeed());
        position.fma(variant.getMoveSpeed() * dt, scratch);
    }

    private void tickWindup(@Nonnull final Store<EntityStore> store,
                            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                            @Nonnull final TitanComponent titan,
                            @Nonnull final TitanVariantAsset variant,
                            @Nonnull final TransformComponent transform,
                            final float dt) {
        turnTowards(titan, transform.getPosition(), targetPosition, variant.getTurnSpeed() * 1.5f, dt);

        // Aimed for the first part of the windup, then left alone. Marking a spot the titan is still turning
        // away from would be a lie, and marking one it is still tracking you onto would be useless.
        if (!hasCommitted(titan, WINDUP_SECONDS)) {
            TitanSmashAttack.resolveImpactPoint(
                transform.getPosition(),
                titan.getTarget() != null ? targetPosition : null,
                titan.getYaw(),
                variant.getAttackRange(),
                titan.getAttackPoint());
        }

        telegraphCircle(store, commandBuffer, titan, variant, titan.getAttackPoint(),
            variant.getAttackRadius(), WINDUP_SECONDS - titan.getStateTime(), dt);

        if (titan.getStateTime() >= WINDUP_SECONDS) {
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
     * Hauls the chosen leg up and marks the ground under where it is going to come down.
     *
     * <p>The landing spot is decided on the first tick and never moved. Everything else the titan could do
     * about a target that walks away is unavailable to it — it cannot chase and cannot reach — so the one
     * counterplay this attack has is stepping out of the circle, and tracking would take that away.
     *
     * <p>The foot itself is only lifted, not swung: a leg holding up a corner of something this size cannot
     * reach out without the rest of it falling over. It comes down where it already was.
     */
    private void tickStompWindup(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull final TitanComponent titan,
                                 @Nonnull final TitanVariantAsset variant,
                                 @Nonnull final TransformComponent transform,
                                 final float dt) {

        turnTowards(titan, transform.getPosition(), targetPosition, variant.getTurnSpeed(), dt);
        titan.getVelocity().set(0);

        if (!hasStompFoot(titan)) {
            abandonStomp(titan, variant);
            return;
        }

        final float windup = Math.max(0.01f, variant.getStompWindupSeconds());
        final double lift = skeletonLift(titan, variant);
        final double progress = Math.min(1.0, titan.getStateTime() / windup);

        // Eased so the leg comes up fast and hangs at the top, which is where the danger is being read
        // from. A linear rise spends the whole windup still climbing and never looks committed.
        titan.getStompGoal().set(titan.getAttackPoint());
        titan.getStompGoal().y += lift * Math.sin(progress * Math.PI * 0.5);

        telegraphCircle(store, commandBuffer, titan, variant, titan.getAttackPoint(),
            variant.getStompRadius(), windup - titan.getStateTime(), dt);

        if (titan.getStateTime() >= windup) {
            titan.setState(TitanState.STOMP);
        }
    }

    /** Drives the leg back down. The blast fires as it lands rather than partway, since the foot is the blow. */
    private void tickStomp(@Nonnull final Store<EntityStore> store,
                           @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                           @Nonnull final Ref<EntityStore> self,
                           @Nonnull final TitanComponent titan,
                           @Nonnull final TitanVariantAsset variant) {

        if (!hasStompFoot(titan)) {
            abandonStomp(titan, variant);
            return;
        }

        final float fall = Math.max(0.01f, variant.getStompSeconds());
        final double lift = skeletonLift(titan, variant);
        final double progress = Math.min(1.0, titan.getStateTime() / fall);

        titan.getVelocity().set(0);
        titan.getStompGoal().set(titan.getAttackPoint());
        // Squared so it accelerates into the ground under its own weight rather than being lowered.
        titan.getStompGoal().y += lift * (1.0 - progress * progress);

        if (!titan.isImpactFired() && titan.getStateTime() >= fall) {
            titan.setImpactFired(true);
            final String sound = variant.getStompSound() != null ? variant.getStompSound() : variant.getImpactSound();
            TitanSmashAttack.execute(store, commandBuffer, self, titan.getAttackPoint(),
                variant.getStompRadius(), variant.getStompDamage(), variant.getStompKnockback(),
                TitanSmashAttack.VERTICAL_SHARE, variant.getImpactParticle(), sound);
            titan.setState(TitanState.STOMP_RECOVER);
        }
    }

    /**
     * Holds the leg planted for a moment, then hands it back to the gait.
     *
     * <p>Clearing the stomp foot is what releases it: until then the animation system is driving that leg
     * from {@code stompGoal} and the walk planner is not allowed near it.
     */
    private void tickStompRecover(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        titan.getStompGoal().set(titan.getAttackPoint());
        titan.getVelocity().set(0);

        if (titan.getStateTime() >= variant.getStompRecoverSeconds()) {
            releaseStomp(titan);
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.IDLE);
        }
    }

    private static boolean hasStompFoot(@Nonnull final TitanComponent titan) {
        final int foot = titan.getStompFoot();
        return foot >= 0 && foot < titan.getFeet().length;
    }

    /**
     * Gives up on a stomp that has lost its leg, which can only happen if the rig changed underneath it.
     * Goes through the same release as a completed one so the foot is never left pinned to a stale goal.
     */
    private static void abandonStomp(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        releaseStomp(titan);
        titan.setAttackCooldown(variant.getAttackCooldown());
        titan.setState(TitanState.IDLE);
    }

    /** Hands the stomping leg back to the walk planner, leaving it planted where it landed. */
    private static void releaseStomp(@Nonnull final TitanComponent titan) {
        if (hasStompFoot(titan)) {
            final var state = titan.getFeet()[titan.getStompFoot()];
            state.current.set(titan.getAttackPoint());
            state.planted.set(titan.getAttackPoint());
            state.stepping = false;
            state.stepProgress = 0f;
        }
        titan.setStompFoot(-1);
    }

    /** How far a stomping foot is hauled off the ground, in world blocks. */
    private static double skeletonLift(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        final var skeleton = titan.getSkeleton();
        final double hip = skeleton == null ? 6.0 : skeleton.getHipHeight();
        return hip * titan.getScale() * variant.getStompLift();
    }

    /**
     * Rears back, then throws the whole body forward onto the floor.
     *
     * <p>The impact point is fixed here rather than tracked through the drop, so the slam is dodgeable in
     * the same way the arm smash is: commit to a spot, and the target has the fall to get out of it.
     */
    private void tickSlamWindup(@Nonnull final Store<EntityStore> store,
                                @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final TitanComponent titan,
                                @Nonnull final TitanVariantAsset variant,
                                @Nonnull final TransformComponent transform,
                                final float dt) {
        turnTowards(titan, transform.getPosition(), targetPosition, variant.getTurnSpeed() * 1.5f, dt);

        // Lands under the chest rather than on the target: the titan is falling on its own front, and
        // aiming the blast at the player would let it belly-flop sideways onto someone stood beside it.
        // Tracked through the windup so the braced arms follow the turn, and frozen once SLAM begins.
        TitanSmashAttack.resolveImpactPoint(
            transform.getPosition(), null, titan.getYaw(), SLAM_REACH, titan.getAttackPoint());

        telegraphCircle(store, commandBuffer, titan, variant, titan.getAttackPoint(),
            variant.getSlamRadius(), SLAM_WINDUP_SECONDS - titan.getStateTime(), dt);

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
     * Hauls both fists overhead.
     *
     * <p>Aimed at a fixed spot in front rather than at the target, because the pound is not a strike at
     * anybody: it is a shock that goes out from between the fists in every direction. Marking that circle
     * and stepping out of it is the entire counterplay.
     */
    private void tickPoundWindup(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull final TitanComponent titan,
                                 @Nonnull final TitanVariantAsset variant,
                                 @Nonnull final TransformComponent transform,
                                 final float dt) {

        if (!hasCommitted(titan, POUND_WINDUP_SECONDS)) {
            turnTowards(titan, transform.getPosition(), targetPosition, variant.getTurnSpeed() * 1.5f, dt);
            TitanSmashAttack.resolveImpactPoint(
                transform.getPosition(), null, titan.getYaw(), POUND_REACH * titan.getScale(), titan.getAttackPoint());
        }

        telegraphCircle(store, commandBuffer, titan, variant, titan.getAttackPoint(),
            variant.getPoundRadius(), POUND_WINDUP_SECONDS - titan.getStateTime(), dt);

        if (titan.getStateTime() >= POUND_WINDUP_SECONDS) {
            titan.setState(TitanState.POUND);
        }
    }

    private void tickPound(@Nonnull final Store<EntityStore> store,
                           @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                           @Nonnull final Ref<EntityStore> self,
                           @Nonnull final TitanComponent titan,
                           @Nonnull final TitanVariantAsset variant) {

        if (!titan.isImpactFired() && titan.getStateTime() >= POUND_IMPACT_SECONDS) {
            titan.setImpactFired(true);
            TitanSmashAttack.execute(store, commandBuffer, self, titan.getAttackPoint(),
                variant.getPoundRadius(), variant.getPoundDamage(), variant.getPoundLaunch(),
                POUND_VERTICAL_SHARE,
                variant.getImpactParticle(),
                variant.getPoundSound() != null ? variant.getPoundSound() : variant.getImpactSound());
        }

        if (titan.getStateTime() >= POUND_SECONDS) {
            titan.setState(TitanState.POUND_STUNNED);
        }
    }

    private void tickPoundStunned(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= variant.getPoundStunSeconds()) {
            titan.setState(TitanState.POUND_RECOVER);
        }
    }

    private void tickPoundRecover(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= POUND_RECOVER_SECONDS) {
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.CHASE);
        }
    }

    /**
     * Digs a boulder out of the ground.
     *
     * <p>Two markers, saying different things. A small one beside the titan is where the rock is coming
     * from, which is only a tell; the one out at the target is where it is going, and that is the one worth
     * moving out of. It keeps tracking through the windup because the throw has not been aimed yet.
     */
    private void tickHurlWindup(@Nonnull final Store<EntityStore> store,
                                @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final TitanComponent titan,
                                @Nonnull final TitanVariantAsset variant,
                                @Nonnull final TransformComponent transform,
                                final float dt) {

        turnTowards(titan, transform.getPosition(), targetPosition, variant.getTurnSpeed() * 1.5f, dt);
        TitanSmashAttack.resolveImpactPoint(
            transform.getPosition(), null, titan.getYaw(), HURL_RIP_REACH * titan.getScale(), titan.getAttackPoint());

        // Fired once, at the frame the rock comes free rather than at the start of the dig.
        if (!titan.isImpactFired() && titan.getStateTime() >= HURL_RIP_SECONDS) {
            titan.setImpactFired(true);
            TitanTelegraph.burst(commandBuffer, variant.getTelegraphCrackParticle(),
                titan.getAttackPoint(), (float) titan.getScale());
            TitanSound.play(commandBuffer, variant.getHurlRipSound(), titan.getAttackPoint());
        }

        telegraphCircle(store, commandBuffer, titan, variant, targetPosition,
            variant.getHurlRadius(), HURL_WINDUP_SECONDS - titan.getStateTime(), dt);

        if (titan.getStateTime() >= HURL_WINDUP_SECONDS) {
            titan.setState(TitanState.HURL);
        }
    }

    private void tickHurl(@Nonnull final Store<EntityStore> store,
                          @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                          @Nonnull final Ref<EntityStore> self,
                          @Nonnull final TitanComponent titan,
                          @Nonnull final TitanVariantAsset variant,
                          @Nonnull final TitanSkeletonAsset skeleton,
                          @Nonnull final TransformComponent transform) {

        if (!titan.isImpactFired() && titan.getStateTime() >= HURL_RELEASE_SECONDS) {
            titan.setImpactFired(true);

            final double scale = titan.getScale();
            TitanBoulder.resolveReleasePoint(transform.getPosition(), titan.getYaw(),
                HURL_RELEASE_REACH * scale, HURL_RELEASE_HEIGHT * scale, scratchPoint);

            final String prefab = TitanBoulder.resolvePrefab(variant, handPrefab(titan, skeleton));
            if (prefab != null) {
                TitanBoulder.throwAt(store, self, variant, prefab, scratchPoint, targetPosition, (float) scale);
            }
            TitanSound.play(commandBuffer, variant.getHurlThrowSound(), scratchPoint);
        }

        if (titan.getStateTime() >= HURL_SECONDS) {
            titan.setState(TitanState.HURL_RECOVER);
        }
    }

    private void tickHurlRecover(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= HURL_RECOVER_SECONDS) {
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.CHASE);
        }
    }

    /** The prefab of the throwing arm's hand, so a titan throws a piece of itself. */
    @Nullable
    private static String handPrefab(@Nonnull final TitanComponent titan, @Nonnull final TitanSkeletonAsset skeleton) {
        final int chain = titan.findHandChainForSide(skeleton, titan.getAttackSide());
        if (chain < 0) return null;

        final int[] bones = skeleton.getIkChains()[titan.getHandChains()[chain]].getBoneIndices();
        if (bones.length == 0) return null;

        return skeleton.getBones()[bones[bones.length - 1]].getPrefab();
    }

    /**
     * Rears up and pitches the front of the slab towards the floor.
     *
     * <p>Marked as a corridor rather than a circle, because unlike everything else the titan does this
     * attack has a direction and getting out of it means stepping aside rather than backing off. The
     * corridor is drawn slightly longer than the run will actually be, so nobody is caught by the last
     * stride.
     */
    private void tickPlowWindup(@Nonnull final Store<EntityStore> store,
                                @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final TitanComponent titan,
                                @Nonnull final TitanVariantAsset variant,
                                @Nonnull final TransformComponent transform,
                                final float dt) {

        // Stops turning partway through, so the corridor drawn on the ground is the one it will run down
        // rather than one last frame of a line that kept swinging round after the player.
        if (!hasCommitted(titan, PLOW_WINDUP_SECONDS)) {
            turnTowards(titan, transform.getPosition(), targetPosition, variant.getTurnSpeed(), dt);
        }
        TitanSmashAttack.resolveImpactPoint(
            transform.getPosition(), null, titan.getYaw(), PLOW_BLADE_REACH * titan.getScale(), titan.getAttackPoint());

        final float remaining = PLOW_WINDUP_SECONDS - titan.getStateTime();
        if (titan.consumePulse(dt, TitanTelegraph.pulseInterval(remaining))) {
            TitanTelegraph.corridor(commandBuffer, store.getExternalData().getWorld().getChunkStore(),
                variant.getTelegraphLineParticle(), transform.getPosition(), titan.getYaw(),
                variant.getPlowSpeed() * variant.getPlowSeconds() * PLOW_TELEGRAPH_LEAD,
                variant.getPlowRadius());
        }

        if (titan.getStateTime() >= PLOW_WINDUP_SECONDS) {
            titan.setState(TitanState.PLOW);
            TitanSound.play(commandBuffer, variant.getPlowSound(), transform.getPosition());
        }
    }

    /**
     * Grinds forward, shovelling whatever is in front and throwing off whoever is on top.
     *
     * <p>The titan does not turn while it is doing this. It committed to a line during the windup and the
     * line is what was shown; steering it into a dodge afterwards would make the corridor a decoration.
     */
    private void tickPlow(@Nonnull final Store<EntityStore> store,
                          @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                          @Nonnull final Ref<EntityStore> self,
                          @Nonnull final TitanComponent titan,
                          @Nonnull final TitanVariantAsset variant,
                          @Nonnull final TitanSkeletonAsset skeleton,
                          @Nonnull final TransformComponent transform,
                          final float dt) {

        final var position = transform.getPosition();
        scratch.set(-Math.sin(titan.getYaw()), 0, -Math.cos(titan.getYaw()));
        titan.getVelocity().set(scratch).mul(variant.getPlowSpeed());
        position.fma(variant.getPlowSpeed() * dt, scratch);

        TitanSmashAttack.resolveImpactPoint(
            position, null, titan.getYaw(), PLOW_BLADE_REACH * titan.getScale(), titan.getAttackPoint());

        // Swept in bursts rather than every tick. The blade covers its own width several times a second, so
        // a continuous sweep would deal the attack's damage twenty times over to anyone who stood still.
        if (titan.consumePulse(dt, PLOW_SWEEP_SECONDS)) {
            TitanSmashAttack.execute(store, commandBuffer, self, titan.getAttackPoint(),
                variant.getPlowRadius(), variant.getPlowDamage(), variant.getAttackKnockback(),
                TitanSmashAttack.VERTICAL_SHARE,
                variant.getImpactParticle(), null);
            throwRiders(store, commandBuffer, titan, variant, skeleton, position);
        }

        if (titan.getStateTime() >= variant.getPlowSeconds()) {
            titan.getVelocity().set(0);
            titan.setPlowCooldown(variant.getPlowCooldown());
            titan.setState(TitanState.PLOW_RECOVER);
        }
    }

    /**
     * Throws anyone still on the back off, backwards and up.
     *
     * <p>Much harder than any other knockback the titan deals, and deliberately so. A rider who is nudged
     * and lands back on the slab has not been removed from anything, and the move exists to remove them.
     * Aimed backwards relative to the titan so they come off over the tail, into the ground it has just
     * driven through rather than the ground it is about to.
     */
    private void throwRiders(@Nonnull final Store<EntityStore> store,
                             @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull final TitanComponent titan,
                             @Nonnull final TitanVariantAsset variant,
                             @Nonnull final TitanSkeletonAsset skeleton,
                             @Nonnull final Vector3d position) {

        final var pose = titan.getPose();
        if (pose == null) return;

        final TitanRider.Back back = TitanRider.measure(skeleton);
        if (back == null) return;

        TitanRider.collect(store, pose, back, position, RIDER_SEARCH_RADIUS * titan.getScale(), riders);
        if (riders.isEmpty()) return;

        final float strength = variant.getPlowRiderKnockback();
        impulse.set(
            Math.sin(titan.getYaw()) * strength * (1.0 - PLOW_RIDER_LIFT),
            strength * PLOW_RIDER_LIFT,
            Math.cos(titan.getYaw()) * strength * (1.0 - PLOW_RIDER_LIFT)
        );

        for (final Ref<EntityStore> rider : riders) {
            TitanSmashAttack.impulse(commandBuffer, rider, impulse);
        }
    }

    /**
     * Beached at the end of the run, then pushes back up.
     *
     * <p>Handed to {@link TitanState#RISING} rather than straight back to the chase, so getting up off the
     * floor is the same shove it takes after a body slam — and so the arms are braced through it, which is
     * what makes both of those recoveries climbable.
     */
    private void tickPlowRecover(@Nonnull final TitanComponent titan,
                                 @Nonnull final TitanVariantAsset variant,
                                 @Nonnull final TransformComponent transform) {

        titan.getVelocity().set(0);
        TitanSmashAttack.resolveImpactPoint(
            transform.getPosition(), null, titan.getYaw(), SLAM_REACH * titan.getScale(), titan.getAttackPoint());

        if (titan.getStateTime() >= variant.getPlowBeachedSeconds()) {
            titan.setState(TitanState.RISING);
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

    /**
     * Notes that a naturally sited titan has been beaten, so its spot stays empty for a while and stays
     * empty across a restart.
     *
     * <p>Recorded here, at the moment the last ore node goes, rather than by watching for the entity to
     * disappear. The engine also removes titans for reasons that are not deaths, such as the ground they
     * are standing on falling out of simulation range, and those must not count.
     */
    private void recordKill(@Nonnull final Store<EntityStore> store, @Nonnull final TitanComponent titan) {
        if (siteMemoryType == null || titan.getSiteCell() == TitanComponent.NO_SITE) return;
        store.getResource(siteMemoryType).markCleared(titan.getSiteCell(), KILL_COOLDOWN);
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
