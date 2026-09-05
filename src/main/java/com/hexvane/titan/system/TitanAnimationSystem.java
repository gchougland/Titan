package com.hexvane.titan.system;

import com.hexvane.titan.anim.TitanClipLibrary;
import com.hexvane.titan.anim.TitanPose;
import com.hexvane.titan.asset.TitanIkChainDef;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.ik.FabrikSolver;
import com.hexvane.titan.ik.FootState;
import com.hexvane.titan.ik.IkMath;
import com.hexvane.titan.ik.TitanFootPlanner;
import com.hexvane.titan.ik.TwoBoneIkSolver;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Turns titan state into bone matrices: play a clip, sample it, override the limbs with IK, then fold
 * everything into world space.
 *
 * <p>{@link TitanPartSyncSystem} reads the result, so this must run first; the AI must run before both so
 * the pose reflects the state decided this tick.
 */
public final class TitanAnimationSystem extends EntityTickingSystem<EntityStore> {

    /** Longest IK chain the pre-allocated FABRIK buffers support. */
    private static final int MAX_CHAIN_BONES = 8;

    /**
     * Seconds between pose rebuilds for a sleeping titan. Its only motion is a twelve-second breathing
     * swell, so this still leaves several updates per cycle.
     */
    private static final float SLEEP_POSE_INTERVAL = 0.25f;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanComponent.getComponentType(), TransformComponent.getComponentType());
    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(new SystemDependency<>(Order.AFTER, TitanAiSystem.class));

    @Nonnull
    private final Matrix4d rootMatrix = new Matrix4d();
    @Nonnull
    private final TwoBoneIkSolver.Result ikResult = new TwoBoneIkSolver.Result();
    @Nonnull
    private final IkMath.Scratch ikScratch = new IkMath.Scratch();
    @Nonnull
    private final TitanFootPlanner.Scratch gaitScratch = new TitanFootPlanner.Scratch();

    @Nonnull
    private final Vector3d bodyPosition = new Vector3d();
    @Nonnull
    private final Vector3d forward = new Vector3d();
    @Nonnull
    private final Vector3d right = new Vector3d();
    @Nonnull
    private final Vector3d poleWorld = new Vector3d();
    @Nonnull
    private final Vector3d wobbleAxis = new Vector3d();
    @Nonnull
    private final Vector3d twistWorld = new Vector3d();
    @Nonnull
    private final Vector3d footGoal = new Vector3d();
    @Nonnull
    private final Vector3d chainRoot = new Vector3d();
    @Nonnull
    private final Vector3d chainMid = new Vector3d();
    @Nonnull
    private final Vector3d chainEnd = new Vector3d();
    @Nonnull
    private final Quaterniond parentRotation = new Quaterniond();
    @Nonnull
    private final Quaterniond upperWorld = new Quaterniond();
    @Nonnull
    private final Quaterniond lowerWorld = new Quaterniond();
    @Nonnull
    private final Quaterniond localRotation = new Quaterniond();
    @Nonnull
    private final Quaterniond levelRotation = new Quaterniond();

    @Nonnull
    private final Vector3d[] fabrikJoints = new Vector3d[MAX_CHAIN_BONES];
    @Nonnull
    private final double[] fabrikLengths = new double[MAX_CHAIN_BONES];
    @Nonnull
    private final Quaterniond[] fabrikWorld = new Quaterniond[MAX_CHAIN_BONES];

    public TitanAnimationSystem() {
        for (int i = 0; i < MAX_CHAIN_BONES; i++) {
            fabrikJoints[i] = new Vector3d();
            fabrikWorld[i] = new Quaterniond();
        }
    }

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
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (titan == null || transform == null) return;

        final TitanSkeletonAsset skeleton = titan.getSkeleton();
        final TitanPose pose = titan.getPose();
        final var animator = titan.getAnimator();
        if (skeleton == null || pose == null || animator == null) return;

        final boolean restarted = titan.consumeClipDirty();
        if (restarted) {
            animator.play(TitanClipLibrary.get(skeleton, resolveClipName(titan)), true);
        }

        float advance = dt;
        if (!restarted && titan.getState() == TitanState.SLEEPING) {
            advance = titan.consumeSleepInterval(dt, SLEEP_POSE_INTERVAL);
            if (advance <= 0f) {
                titan.setPoseDirty(false);
                return;
            }
        }

        titan.setPoseDirty(true);
        animator.advance(advance);
        animator.sampleInto(skeleton, pose);
        applyWobble(titan, skeleton, pose, advance);
        poseBones(dt, titan, skeleton, pose, transform, store, commandBuffer);

        // Must run on the finished pose, and poseBones has more than one exit.
        pose.captureMotion();
    }

    /**
     * Sways the bones a skeleton declares a wobble for, on top of whatever the clip left them at.
     *
     * <p>Between clip sampling and the IK pass on purpose. Composed onto the local rotation rather than
     * replacing it, so a bone that a clip does animate gets both; and applied before the IK, so a wobble
     * mistakenly authored on a bone inside a chain is quietly overwritten instead of tearing the limb.
     *
     * <p>The phase runs off the animator's own clock rather than a wall clock, so a titan being posed at a
     * reduced rate while asleep sways at the right speed instead of jumping between samples.
     */
    private void applyWobble(@Nonnull final TitanComponent titan,
                             @Nonnull final TitanSkeletonAsset skeleton,
                             @Nonnull final TitanPose pose,
                             final float advance) {

        final var wobbles = skeleton.getProceduralWobble();
        if (wobbles.length == 0) return;

        final float time = titan.addWobbleTime(advance);
        final double speed = titan.getVelocity().length();

        for (final var wobble : wobbles) {
            final int bone = wobble.getBoneIndex();
            if (bone < 0 || bone >= pose.getBoneCount()) continue;

            final double reach = wobble.getSpeedScale() <= 0f
                ? 1.0
                : Math.min(1.0, speed / wobble.getSpeedScale());
            final double amplitude = Math.toRadians(
                wobble.getIdleAmplitudeDegrees()
                    + (wobble.getAmplitudeDegrees() - wobble.getIdleAmplitudeDegrees()) * reach);
            if (amplitude == 0.0) continue;

            final double phase = Math.toRadians(wobble.getPhaseDegrees()) + time * wobble.getHz() * 2.0 * Math.PI;
            final double angle = Math.sin(phase) * amplitude;

            final var axis = wobble.getAxis();
            if (axis.lengthSquared() < 1.0e-9) continue;
            wobbleAxis.set(axis).normalize();
            pose.getLocalRotation(bone).rotateAxis(angle, wobbleAxis.x, wobbleAxis.y, wobbleAxis.z);
        }
    }

    /** Turns the sampled clip into world matrices, with the IK correction pass on top where it applies. */
    private void poseBones(final float dt,
                           @Nonnull final TitanComponent titan,
                           @Nonnull final TitanSkeletonAsset skeleton,
                           @Nonnull final TitanPose pose,
                           @Nonnull final TransformComponent transform,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final double scale = titan.getScale();
        TitanPose.rootMatrix(transform.getPosition(), titan.getYaw(), scale, rootMatrix);
        pose.computeWorld(skeleton, rootMatrix);

        // None of these want planted feet: sleeping is a boulder, dying is loose rubble, and emoting plays
        // the clip as authored.
        if (titan.getState() == TitanState.SLEEPING
            || titan.getState() == TitanState.DYING
            || titan.getState() == TitanState.EMOTING) return;

        forward.set(-Math.sin(titan.getYaw()), 0, -Math.cos(titan.getYaw()));
        right.set(Math.cos(titan.getYaw()), 0, -Math.sin(titan.getYaw()));
        pose.getWorldPosition(skeleton.getBodyBoneIndex(), bodyPosition);

        updateFeet(dt, titan, skeleton, store, commandBuffer, scale);
        applyFootIk(titan, skeleton, pose);
        applyHandIk(titan, skeleton, pose);

        pose.computeWorld(skeleton, rootMatrix);
    }

    @Nonnull
    private String resolveClipName(@Nonnull final TitanComponent titan) {
        return switch (titan.getState()) {
            case WINDUP, SMASH -> titan.getAttackSide() < 0 ? "Attack_Arm_L" : "Attack_Arm_R";
            default -> titan.getState().getDefaultClip();
        };
    }

    /**
     * Steps the gait. Only one diagonal group may be airborne at a time, so the titan always has half its
     * feet on the ground.
     *
     * <p>Which group is airborne is counted twice: once from the feet already stepping when the tick began,
     * and again as each foot is given its turn. Only the first count was there to begin with, and it left a
     * hole exactly one tick wide — the tick where every foot is planted and the body has dragged them all
     * past their stride. Nothing was stepping, so nothing was blocked, so both groups set off together and
     * the walk came out as a two-footed hop. Marking the group as each foot commits closes it: the first
     * group to be asked goes, the other waits for it to land, and the legs alternate from then on.
     */
    private void updateFeet(final float dt,
                            @Nonnull final TitanComponent titan,
                            @Nonnull final TitanSkeletonAsset skeleton,
                            @Nonnull final Store<EntityStore> store,
                            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                            final double scale) {

        final FootState[] feet = titan.getFeet();
        if (feet.length == 0) return;

        final ChunkStore chunkStore = store.getExternalData().getWorld().getChunkStore();
        final int[] chains = titan.getFootChains();
        final var ikChains = skeleton.getIkChains();

        boolean groupZeroStepping = false;
        boolean groupOneStepping = false;
        for (final FootState foot : feet) {
            if (!foot.stepping) continue;
            if (foot.gaitGroup == 0) groupZeroStepping = true;
            else groupOneStepping = true;
        }

        final int stomping = titan.getStompFoot();

        for (int i = 0; i < feet.length; i++) {
            // A leg mid-stomp belongs to the AI, which holds it in the air over a marked spot. Letting the
            // planner touch it as well would drag the foot back to wherever the gait wants it.
            if (i == stomping) {
                feet[i].current.set(titan.getStompGoal());
                continue;
            }

            final var chain = ikChains[chains[i]];
            final boolean blocked = feet[i].gaitGroup == 0 ? groupOneStepping : groupZeroStepping;
            final boolean stepping = TitanFootPlanner.update(feet[i], chain, bodyPosition, forward, right,
                titan.getVelocity(), scale, chunkStore, dt, !blocked, gaitScratch);

            if (stepping) {
                if (feet[i].gaitGroup == 0) groupZeroStepping = true;
                else groupOneStepping = true;
            }

            if (feet[i].justLanded) {
                feet[i].justLanded = false;
                final TitanVariantAsset variant = titan.getVariant();
                if (variant != null) {
                    TitanSound.play(commandBuffer, variant.getStepSound(), feet[i].planted);
                }
            }
        }
    }

    private void applyFootIk(@Nonnull final TitanComponent titan,
                             @Nonnull final TitanSkeletonAsset skeleton,
                             @Nonnull final TitanPose pose) {
        final FootState[] feet = titan.getFeet();
        final int[] chains = titan.getFootChains();
        // Taken off the goal rather than off the planted spot, so the gait carries on planning against the
        // surface it is standing on and a titan that stands back up finds its feet already where they were.
        final double sink = titan.getFootSink();
        for (int i = 0; i < feet.length; i++) {
            if (!feet[i].initialised) continue;
            final Vector3d goal = footGoal.set(feet[i].current);
            goal.y -= sink;
            solveChain(skeleton, pose, skeleton.getIkChains()[chains[i]], goal, 1f);
        }
    }

    private void applyHandIk(@Nonnull final TitanComponent titan,
                             @Nonnull final TitanSkeletonAsset skeleton,
                             @Nonnull final TitanPose pose) {
        final float[] weights = titan.getHandWeights();
        final var goals = titan.getHandGoals();
        final int[] chains = titan.getHandChains();
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0f) continue;
            solveChain(skeleton, pose, skeleton.getIkChains()[chains[i]], goals[i], weights[i]);
        }
    }

    /**
     * Bends one limb towards {@code goal} and writes the result back as local bone rotations, blended
     * against whatever the clip produced.
     */
    private void solveChain(@Nonnull final TitanSkeletonAsset skeleton,
                            @Nonnull final TitanPose pose,
                            @Nonnull final TitanIkChainDef chain,
                            @Nonnull final Vector3d goal,
                            final float weight) {

        final int[] bones = chain.getBoneIndices();
        if (bones.length < 2) return;

        rootMatrix.transformDirection(poleWorld.set(chain.getPoleDirection()));
        if (poleWorld.lengthSquared() < IkMath.EPSILON) {
            poleWorld.set(forward);
        } else {
            poleWorld.normalize();
        }

        // Only feet are levelled: a hand should follow the arm into the ground on a smash.
        final boolean levelEnd = chain.getRole() == TitanIkChainDef.Role.FOOT;

        if (chain.getKind() == TitanIkChainDef.Kind.TWO_BONE && bones.length >= 3) {
            solveTwoBone(skeleton, pose, bones, goal, weight, levelEnd);
        } else {
            solveFabrik(skeleton, pose, bones, goal, weight);
        }
    }

    private void solveTwoBone(@Nonnull final TitanSkeletonAsset skeleton,
                              @Nonnull final TitanPose pose,
                              @Nonnull final int[] bones,
                              @Nonnull final Vector3d goal,
                              final float weight,
                              final boolean levelEnd) {

        final int upper = bones[0];
        final int lower = bones[1];
        final int end = bones[2];

        pose.getWorldPosition(upper, chainRoot);
        pose.getWorldPosition(lower, chainMid);
        pose.getWorldPosition(end, chainEnd);

        final double upperLength = chainRoot.distance(chainMid);
        final double lowerLength = chainMid.distance(chainEnd);
        if (upperLength < IkMath.EPSILON || lowerLength < IkMath.EPSILON) return;

        TwoBoneIkSolver.solve(chainRoot, goal, upperLength, lowerLength, poleWorld, ikResult);

        final var boneDefs = skeleton.getBones();
        IkMath.alignAxis(upperWorld, boneDefs[lower].getOffset(), ikResult.upperDirection,
            IkMath.uprightTwist(ikResult.upperDirection, poleWorld, twistWorld), ikScratch);
        IkMath.alignAxis(lowerWorld, boneDefs[end].getOffset(), ikResult.lowerDirection,
            IkMath.uprightTwist(ikResult.lowerDirection, poleWorld, twistWorld), ikScratch);

        worldRotationOfParent(pose, skeleton, upper, parentRotation);
        blendLocal(pose, upper, parentRotation, upperWorld, weight);
        blendLocal(pose, lower, upperWorld, lowerWorld, weight);

        // The solve only orients the thigh and shin. Left alone the foot inherits the shin, so a bent knee
        // tips the sole up off the ground. The root rotation carries the yaw and nothing else, so
        // overriding the foot with it leaves the sole flat.
        if (levelEnd) {
            rootMatrix.getNormalizedRotation(levelRotation);
            blendLocal(pose, end, lowerWorld, levelRotation, weight);
        }
    }

    private void solveFabrik(@Nonnull final TitanSkeletonAsset skeleton,
                             @Nonnull final TitanPose pose,
                             @Nonnull final int[] bones,
                             @Nonnull final Vector3d goal,
                             final float weight) {

        final int count = Math.min(bones.length, MAX_CHAIN_BONES);
        for (int i = 0; i < count; i++) {
            pose.getWorldPosition(bones[i], fabrikJoints[i]);
        }
        for (int i = 0; i < count - 1; i++) {
            fabrikLengths[i] = fabrikJoints[i].distance(fabrikJoints[i + 1]);
            if (fabrikLengths[i] < IkMath.EPSILON) return;
        }

        FabrikSolver.solve(fabrikJoints, fabrikLengths, goal, count);

        final var boneDefs = skeleton.getBones();
        worldRotationOfParent(pose, skeleton, bones[0], parentRotation);

        for (int i = 0; i < count - 1; i++) {
            chainEnd.set(fabrikJoints[i + 1]).sub(fabrikJoints[i]);
            if (chainEnd.lengthSquared() < IkMath.EPSILON) continue;
            chainEnd.normalize();

            IkMath.alignAxis(fabrikWorld[i], boneDefs[bones[i + 1]].getOffset(), chainEnd,
                IkMath.uprightTwist(chainEnd, poleWorld, twistWorld), ikScratch);
            blendLocal(pose, bones[i], i == 0 ? parentRotation : fabrikWorld[i - 1], fabrikWorld[i], weight);
        }
    }

    /**
     * Converts a desired world rotation into the bone's local space and eases the existing local rotation
     * towards it.
     */
    private void blendLocal(@Nonnull final TitanPose pose,
                            final int bone,
                            @Nonnull final Quaterniond parentWorld,
                            @Nonnull final Quaterniond desiredWorld,
                            final float weight) {
        parentWorld.invert(localRotation).mul(desiredWorld).normalize();
        if (weight >= 1f) {
            pose.getLocalRotation(bone).set(localRotation);
        } else {
            pose.getLocalRotation(bone).slerp(localRotation, weight).normalize();
        }
    }

    private void worldRotationOfParent(@Nonnull final TitanPose pose,
                                       @Nonnull final TitanSkeletonAsset skeleton,
                                       final int bone,
                                       @Nonnull final Quaterniond dest) {
        final int parent = skeleton.getBones()[bone].getParentIndex();
        if (parent < 0) {
            rootMatrix.getNormalizedRotation(dest);
        } else {
            pose.getWorld(parent).getNormalizedRotation(dest);
        }
    }
}
