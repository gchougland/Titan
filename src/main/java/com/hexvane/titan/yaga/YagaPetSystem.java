package com.hexvane.titan.yaga;

import com.hexvane.titan.ai.TitanAiScratch;
import com.hexvane.titan.ai.TitanAiSupport;
import com.hexvane.titan.ai.TitanBodyDriver;
import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.ik.GroundSampler;
import com.hexvane.titan.system.TitanAnimationSystem;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

/**
 * Drives a hatched Baba Yaga house: where it walks, how low it sits, and when it leaves the ground.
 *
 * <p>Three things can be telling it where to go, and they are settled here in one place because they are
 * all the same question. A leap it is already in the middle of finishes first, since it is in the air and
 * nothing on the ground applies. A wand pointed at it comes next — see {@link YagaWand} — because being
 * told is more urgent than being left to its own devices. Failing both, it does whatever its mode says:
 * walks after its owner, or stays folded up where it is.
 *
 * <p>What {@link com.hexvane.titan.system.TitanAiSystem} is to a combat titan, this is to a pet. That
 * system skips them outright rather than growing a mode for them — it decides a state, a target, a velocity
 * and an attack every tick, and a house that follows one player and otherwise sits down needs none of it —
 * so the handful of things every titan does need are repeated here: the state clock, the yaw written onto
 * the transform, and the body settled onto the ground.
 *
 * <p>Nothing about the legs is here. The gait planner steps them off the body's velocity, so telling the
 * house to walk and telling it to crouch are both just numbers this writes, and the chicken-leg fold comes
 * out of the inverse kinematics for free.
 */
public final class YagaPetSystem extends EntityTickingSystem<EntityStore> {

    /**
     * How fast the resting crouch folds and unfolds, in units of crouch per second.
     *
     * <p>Slow enough to read as the house lowering itself. The feet stay planted through it, so the legs
     * fold under the body over the same second and a player watching sees it kneel.
     */
    private static final float CROUCH_RATE = 0.8f;

    /**
     * How much closer than its follow distance the house has to be before it stops walking, as a fraction
     * of that distance.
     *
     * <p>A plain radius makes it start and stop every tick while the player mills about at the edge of it:
     * it stops on arrival, the player drifts a foot away, and it sets off again. Coming a little inside the
     * distance before stopping gives the pair somewhere to stand still together.
     */
    private static final double FOLLOW_HYSTERESIS = 0.75;

    /**
     * How much of the way into a rest is spent folding the legs before the house starts sinking, as a
     * fraction of the whole movement.
     *
     * <p>The two halves of sitting down look wrong together and right in order. Both at once drags the feet
     * down through the ground while the legs are still at full stretch, so what a player sees is a house
     * standing in a hole with its ankles buried. Folding first puts the feet under the body where they
     * belong, and only then does the whole rig bed into the earth — by which point the feet are tucked
     * beneath a body that is nearly on the ground, and there is nothing left to see them do.
     *
     * <p>Unfolds in the same order reversed, for free: standing up lifts the rig clear of the ground before
     * the legs take any weight, which is the same movement backwards.
     */
    private static final float FOLD_SHARE = 0.5f;

    /** How much crouch still counts as standing, for the things a folded house cannot do. */
    private static final float STOOD_UP = 0.05f;

    /**
     * Minimum alignment with the wand, as a cosine, before a directed house walks rather than turns.
     *
     * <p>The same reasoning as the pet's own walking: a house that set off before it had come round would
     * crab sideways across the ground with its legs at an angle to its travel.
     */
    private static final double WALK_FACING_THRESHOLD = 0.7;

    /** How far ahead of the house the wand's heading is aimed, in blocks. See {@code aim}. */
    private static final double AIM_REACH = 50.0;

    /**
     * The gravity a leaping house falls under, in blocks per second squared.
     *
     * <p>Its own rather than the engine's, since nothing about a titan goes through the physics: the body
     * is a position this system writes every tick. Heavier than a player's, because a house the size of a
     * barn floating gently down looks weightless.
     */
    private static final double GRAVITY = 42.0;

    /** Vertical search window for the ground a leap is coming down onto, in blocks. */
    private static final int LEAP_GROUND_ABOVE = 2;
    private static final int LEAP_GROUND_BELOW = 48;

    /**
     * The fastest a leap is allowed to be falling before it ends wherever it is, in blocks per second.
     *
     * <p>A leap over a column the server has not loaded finds no ground to land on, and without this the
     * house would keep accelerating downwards past the bottom of the world. Ending the leap hands it back
     * to the ordinary body settling, which knows how to wait for terrain; the house hangs in the air until
     * the chunk arrives rather than being lost under it.
     */
    private static final double LEAP_TERMINAL = 80.0;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanComponent.getComponentType(),
        YagaComponent.getComponentType(),
        TransformComponent.getComponentType());

    /**
     * Before the pose is built, so the legs are solved against the body position decided this tick rather
     * than last tick's, which is a frame of lag between where the house is and where its feet think it is.
     */
    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency<>(Order.BEFORE, TitanAnimationSystem.class));

    /** Working values. Ticking systems run on the world thread by default, so one set covers every house. */
    @Nonnull
    private final TitanAiScratch scratch = new TitanAiScratch();
    @Nonnull
    private final Vector3d ownerPosition = new Vector3d();
    @Nonnull
    private final Vector3d goal = new Vector3d();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void tick(final float dt,
                     final int index,
                     @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final var titan = archetypeChunk.getComponent(index, TitanComponent.getComponentType());
        final var yaga = archetypeChunk.getComponent(index, YagaComponent.getComponentType());
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (titan == null || yaga == null || transform == null) return;

        if (!titan.refreshAssets()) {
            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
            return;
        }

        final var variant = titan.getVariant();
        if (variant == null) return;

        titan.addStateTime(dt);

        // Two things happen on the way down, and only the first of them is a crouch. Holding the root
        // below the ground folds the legs, because the feet stay where the planner put them and the chain
        // has to take up the difference — but a leg only folds so far, and on a house that leaves the
        // floor well out of a player's reach. The rest of the way the feet come too, so the whole rig
        // keeps its shape and beds into the ground instead. See TitanVariantAsset.getRestSink.
        //
        // One after the other, not together: see FOLD_SHARE.
        final double fold = variant.getCrouchDepth();
        final double settle = variant.getRestSink();

        // Ramped both ways, so telling it to stand up unfolds at the same speed it knelt, and never faster
        // than the body can actually settle. A house thirteen blocks deep in the ground at the ordinary
        // rate would finish crouching in a second and a quarter while the body was still three seconds
        // from arriving — and a body that far behind where it is being told to be is a body that has lost
        // sight of the ground it is measuring against, which is a house that sits down and cannot get up.
        //
        // Measured against the steeper of the two halves, since that is the one that would outrun it.
        final double steepest = Math.max(fold / FOLD_SHARE, settle / (1f - FOLD_SHARE));
        final float rate = steepest > 0
            ? (float) Math.min(CROUCH_RATE, TitanBodyDriver.BODY_HEIGHT_FOLLOW / steepest)
            : CROUCH_RATE;

        final float goal = yaga.getMode() == YagaComponent.Mode.RESTING ? 1f : 0f;
        final float crouch = yaga.getCrouch();
        yaga.setCrouch(crouch + Math.copySign(Math.min(rate * dt, Math.abs(goal - crouch)), goal - crouch));

        final double folded = Math.min(yaga.getCrouch() / FOLD_SHARE, 1f);
        final double settled = Math.clamp((yaga.getCrouch() - FOLD_SHARE) / (1f - FOLD_SHARE), 0f, 1f);

        final double sink = fold * folded + settle * settled;
        titan.setFootSink(settle * settled);

        // IDLE rather than CHASE even while walking. A pet is never chasing anything, and the state only
        // picks the clip and the gait's own idea of what the body is doing; these skeletons ship no clips,
        // and the planner reads the velocity written below rather than the state.
        titan.setState(TitanState.IDLE);

        // Who its owner is and what they are asking of it, worked out once: the wand, the follow and the
        // check for somebody standing on the floor all want the same player.
        final UUID ownerUuid = yaga.getOwnerUuid();
        final Ref<EntityStore> owner = owner(store, yaga);

        // Both asks are ignored outright unless the wand is the thing in their hand. Putting it away is the
        // one end to a hold that cannot go astray, so it is checked here rather than trusted to arrive.
        final boolean holding = owner != null && ownerUuid != null && YagaWand.inHand(store, owner);
        final boolean directed = holding && YagaWand.isPointing(ownerUuid);

        if (holding && YagaWand.consumeLeap(ownerUuid)) {
            takeOff(store, yaga, titan, transform, owner);
        }

        if (yaga.isLeaping()) {
            // Airborne, so the ground is not where the body goes and the owner is not where it is headed.
            // The heading was fixed at take-off and the arc runs itself out from there.
            fly(store, yaga, titan, transform, dt);
            return;
        }

        if (directed) {
            // Being pointed somewhere beats both modes. A house told to go while it is sitting down gets
            // up first — setting the mode is what unwinds the crouch above — and walks once it is on its
            // feet, which reads as it being roused rather than dragged.
            if (yaga.getMode() == YagaComponent.Mode.RESTING) yaga.setMode(YagaComponent.Mode.FOLLOW);
            if (!steer(store, yaga, titan, transform, owner, dt)) titan.getVelocity().set(0);
        } else if (yaga.getMode() == YagaComponent.Mode.RESTING || !follow(store, titan, transform, owner, dt)) {
            titan.getVelocity().set(0);
        }

        transform.getRotation().setYaw(titan.getYaw());
        TitanBodyDriver.settleBodyHeight(store, transform, dt, sink);
    }

    /**
     * Sends the house walking the way the wand is pointing.
     *
     * <p>The heading is the owner's own, which is the whole of the control: they look where they want it to
     * go and hold a button, and the house turns onto that heading and walks it. Nothing is read from their
     * keys, their position or their movement, so nothing about them changes while they are doing it — they
     * can walk alongside, stand on the floor, or watch from a hilltop, and the house behaves the same.
     *
     * <p>Aimed at a point rather than given a direction only because the walking helper takes one. Fifty
     * blocks ahead of where the house is standing is far enough that it is always walking towards
     * something, and the point is recomputed every tick, so turning the wand turns the house.
     *
     * @return whether it had somewhere to go, {@code false} while it is still getting to its feet
     */
    private boolean steer(@Nonnull final Store<EntityStore> store,
                          @Nonnull final YagaComponent yaga,
                          @Nonnull final TitanComponent titan,
                          @Nonnull final TransformComponent transform,
                          @Nonnull final Ref<EntityStore> owner,
                          final float dt) {

        final var variant = titan.getVariant();
        final var look = store.getComponent(owner, TransformComponent.getComponentType());
        if (variant == null || look == null) return false;

        final float heading = look.getRotation().yaw();
        final var position = transform.getPosition();

        // Turned onto the heading whatever else is happening, so a folded house is already facing the right
        // way by the time its legs are under it.
        TitanAiSupport.turnTowards(titan, position, aim(position, heading), variant.getWandTurnSpeed(), dt);

        // Walking with the legs still folded drags the body along the ground. The crouch unwinds in about a
        // second, so this is a moment's pause at the start of the first command after a rest.
        if (yaga.getCrouch() > STOOD_UP) return false;

        final double facing = Math.cos(heading - titan.getYaw());
        if (facing < WALK_FACING_THRESHOLD) {
            titan.getVelocity().set(0);
            return true;
        }

        scratch.direction.set(-Math.sin(titan.getYaw()), 0, -Math.cos(titan.getYaw()));
        titan.getVelocity().set(scratch.direction).mul(variant.getWandSpeed());
        position.fma(variant.getWandSpeed() * dt, scratch.direction);
        return true;
    }

    /**
     * Throws the house into the air on the heading the wand is pointing.
     *
     * <p>Refused rather than queued if it is already in the air or still folded up, so a player leaning on
     * the key gets one leap per press and a resting house has to be told to stand first.
     */
    private void takeOff(@Nonnull final Store<EntityStore> store,
                         @Nonnull final YagaComponent yaga,
                         @Nonnull final TitanComponent titan,
                         @Nonnull final TransformComponent transform,
                         @Nonnull final Ref<EntityStore> owner) {

        final var variant = titan.getVariant();
        if (variant == null || yaga.isLeaping() || yaga.getCrouch() > STOOD_UP || variant.getLeapHeight() <= 0) {
            return;
        }

        final var look = store.getComponent(owner, TransformComponent.getComponentType());
        final float heading = look != null ? look.getRotation().yaw() : titan.getYaw();

        // The speed that reaches the asked-for height under the gravity below, so the height in the variant
        // is the height in blocks rather than a number that has to be found by trying it.
        yaga.leap(Math.sqrt(2 * GRAVITY * variant.getLeapHeight() * titan.getScale()), heading);
        titan.setYaw(heading);

        TitanSound.play(store, variant.getLeapSound(), transform.getPosition());
    }

    /**
     * Runs a leap on from where it got to, and lands it.
     *
     * <p>Plain ballistics, because a house is not a player and there is no movement config to borrow: the
     * body carries its own vertical speed while the ground is ignored, and the leap ends the moment the
     * body catches up with the terrain under it. The horizontal half is the same walk as anything else,
     * which is what keeps the legs stepping through the air rather than trailing behind.
     */
    private void fly(@Nonnull final Store<EntityStore> store,
                     @Nonnull final YagaComponent yaga,
                     @Nonnull final TitanComponent titan,
                     @Nonnull final TransformComponent transform,
                     final float dt) {

        final var variant = titan.getVariant();
        if (variant == null) {
            yaga.land();
            return;
        }

        final var position = transform.getPosition();
        final float yaw = yaga.getLeapYaw();

        titan.setYaw(yaw);
        transform.getRotation().setYaw(yaw);

        scratch.direction.set(-Math.sin(yaw), 0, -Math.cos(yaw));
        titan.getVelocity().set(scratch.direction).mul(variant.getLeapSpeed());
        position.fma(variant.getLeapSpeed() * dt, scratch.direction);

        final double lift = yaga.getLift() - GRAVITY * dt;
        yaga.setLift(lift);
        position.y += lift * dt;

        // Only on the way down, so a leap that starts under an overhang is not landed on the spot by the
        // ground it is still rising through.
        if (lift > 0) return;

        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        final double ground = GroundSampler.sample(
            chunkStore, position.x, position.y, position.z, LEAP_GROUND_ABOVE, LEAP_GROUND_BELOW);

        if (!GroundSampler.isValid(ground)) {
            if (lift < -LEAP_TERMINAL) yaga.land();
            return;
        }

        if (position.y > ground) return;

        position.y = ground;
        yaga.land();
        TitanSound.play(store, variant.getImpactSound(), position);
    }

    /** A point on {@code heading}, far enough ahead of {@code from} to be a direction rather than a target. */
    @Nonnull
    private Vector3d aim(@Nonnull final Vector3d from, final float heading) {
        return goal.set(from.x - Math.sin(heading) * AIM_REACH, from.y, from.z - Math.cos(heading) * AIM_REACH);
    }

    /**
     * Walks the house after its owner and stops it short of them.
     *
     * @return whether it had somewhere to go, {@code false} when the owner is not in this world
     */
    private boolean follow(@Nonnull final Store<EntityStore> store,
                           @Nonnull final TitanComponent titan,
                           @Nonnull final TransformComponent transform,
                           @Nullable final Ref<EntityStore> owner,
                           final float dt) {

        if (owner == null) return false;

        final var ownerTransform = store.getComponent(owner, TransformComponent.getComponentType());
        if (ownerTransform == null) return false;
        ownerPosition.set(ownerTransform.getPosition());

        final var variant = titan.getVariant();
        assert variant != null;

        // Hysteresis only on the near side: it stops well inside its follow distance and does not set off
        // again until the player has left it, so the pair can stand still.
        final double distance = TitanAiSupport.horizontalDistance(transform.getPosition(), ownerPosition);

        // Carrying them is not following them. An owner up on the house moves with every correction it
        // makes towards them, so it would turn on the spot for as long as they stood there.
        if (isAboard(titan, transform, distance)) {
            titan.getVelocity().set(0);
            return true;
        }

        final double follow = variant.getFollowDistance();
        final boolean moving = titan.getVelocity().lengthSquared() > 0;
        final double arrival = moving ? follow * FOLLOW_HYSTERESIS : follow;

        if (distance <= arrival) {
            // Still turned to face them while stopped, so it is looking at its owner rather than wherever
            // it happened to come to rest.
            TitanAiSupport.turnTowards(titan, transform.getPosition(), ownerPosition, variant.getTurnSpeed(), dt);
            titan.getVelocity().set(0);
            return true;
        }

        TitanAiSupport.walkTowards(scratch, titan, variant, transform.getPosition(), ownerPosition, arrival, dt);
        return true;
    }

    /**
     * Whether the owner is standing on the titan rather than beside it.
     *
     * <p>Two questions, because either alone is wrong: within the footprint but at ground level is a
     * player stood among the legs, and high up but well outside it is a player on a hillside. Height is
     * measured against the hips rather than a fixed number of blocks, since the root of a titan is at its
     * feet and half the hip height is comfortably above whatever the legs are standing on and comfortably
     * below the floor of anything it is carrying.
     *
     * @param distance the horizontal distance from the root to the owner, in blocks
     */
    private boolean isAboard(@Nonnull final TitanComponent titan,
                             @Nonnull final TransformComponent transform,
                             final double distance) {

        final var variant = titan.getVariant();
        final var skeleton = titan.getSkeleton();
        if (variant == null || skeleton == null || variant.getAboardRadius() <= 0) return false;

        final double scale = titan.getScale();
        if (distance > variant.getAboardRadius() * scale) return false;

        return ownerPosition.y - transform.getPosition().y > skeleton.getHipHeight() * scale * 0.5;
    }

    /** The owner of this house, if they are in the world with it. */
    @Nullable
    private static Ref<EntityStore> owner(@Nonnull final Store<EntityStore> store,
                                          @Nonnull final YagaComponent yaga) {

        final UUID ownerUuid = yaga.getOwnerUuid();
        if (ownerUuid == null) return null;

        return ownerRef(store.getExternalData().getWorld(), ownerUuid);
    }

    /**
     * The owner's entity, or {@code null} if they are elsewhere.
     *
     * <p>A scan of the world's players rather than a lookup, because there is no index from account UUID to
     * entity and a world holds a few dozen players at most. The owner is held as a UUID in the first place
     * because they log out and come back as a different entity while the house stays where it was.
     */
    @Nullable
    public static Ref<EntityStore> ownerRef(@Nonnull final World world, @Nonnull final UUID ownerUuid) {
        for (final PlayerRef playerRef : world.getPlayerRefs()) {
            if (!ownerUuid.equals(playerRef.getUuid())) continue;

            final Ref<EntityStore> ref = playerRef.getReference();
            return ref != null && ref.isValid() ? ref : null;
        }
        return null;
    }
}
