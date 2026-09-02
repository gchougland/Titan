package com.hexvane.titan.anim;

import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Mutable skeleton state for one titan: a local transform per bone plus the world matrices derived from
 * them.
 *
 * <p>Everything is pre-allocated and reused every tick — a titan re-poses ~20 bones 20 times a second and
 * allocating here would dominate its cost.
 */
public final class TitanPose {

    @Nonnull
    private final Vector3d[] localTranslation;
    @Nonnull
    private final Quaterniond[] localRotation;
    @Nonnull
    private final Matrix4d[] world;

    /** The world matrices as of the last {@link #captureMotion}, i.e. the previous tick's finished pose. */
    @Nonnull
    private final Matrix4d[] settled;
    @Nonnull
    private final boolean[] moved;

    @Nonnull
    private final Quaterniond scratchQuat = new Quaterniond();
    @Nonnull
    private final Vector3d scratchVec = new Vector3d();
    @Nonnull
    private final Matrix4d scratchMatrix = new Matrix4d();

    public TitanPose(final int boneCount) {
        localTranslation = new Vector3d[boneCount];
        localRotation = new Quaterniond[boneCount];
        world = new Matrix4d[boneCount];
        settled = new Matrix4d[boneCount];
        moved = new boolean[boneCount];
        for (int i = 0; i < boneCount; i++) {
            localTranslation[i] = new Vector3d();
            localRotation[i] = new Quaterniond();
            world[i] = new Matrix4d();
            settled[i] = new Matrix4d();
            // Nothing has been captured yet, so assume the worst and let the first tick sort it out.
            moved[i] = true;
        }
    }

    public int getBoneCount() {
        return world.length;
    }

    /**
     * Builds the matrix every bone hangs from: the titan's world position, its body yaw, and the uniform
     * scale that turns model units into blocks.
     */
    @Nonnull
    public static Matrix4d rootMatrix(@Nonnull final org.joml.Vector3dc position,
                                      final float yaw,
                                      final double scale,
                                      @Nonnull final Matrix4d dest) {
        return dest.identity()
            .translate(position.x(), position.y(), position.z())
            .rotateY(yaw)
            .scale(scale);
    }

    /** Local translation of a bone from its parent's pivot, in model units. */
    @Nonnull
    public Vector3d getLocalTranslation(final int bone) {
        return localTranslation[bone];
    }

    @Nonnull
    public Quaterniond getLocalRotation(final int bone) {
        return localRotation[bone];
    }

    /** World matrix including the titan's position, yaw and scale. Valid after {@link #computeWorld}. */
    @Nonnull
    public Matrix4d getWorld(final int bone) {
        return world[bone];
    }

    /** Resets every bone to the skeleton's bind transform. */
    public void resetToBind(@Nonnull final TitanSkeletonAsset skeleton) {
        final var bones = skeleton.getBones();
        for (int i = 0; i < bones.length && i < localTranslation.length; i++) {
            localTranslation[i].set(bones[i].getOffset());
            final var euler = bones[i].getBindRotationDegrees();
            localRotation[i].identity().rotateXYZ(
                Math.toRadians(euler.x),
                Math.toRadians(euler.y),
                Math.toRadians(euler.z)
            );
        }
    }

    /**
     * Samples {@code clip} at {@code time} on top of the bind pose. Bones the clip does not touch keep
     * their bind transform.
     */
    public void sample(@Nonnull final TitanSkeletonAsset skeleton, @Nonnull final TitanClip clip, final float time) {
        resetToBind(skeleton);
        final float positionScale = skeleton.getAnimationPositionScale();
        for (int i = 0; i < localTranslation.length; i++) {
            final var track = clip.getTrack(i);
            if (track == null) continue;

            if (track.samplePosition(time, scratchVec)) {
                localTranslation[i].add(scratchVec.mul(positionScale));
            }
            if (track.sampleRotation(time, scratchQuat)) {
                localRotation[i].mul(scratchQuat);
            }
        }
    }

    /**
     * Blends {@code other} into this pose. {@code t} of {@code 0} keeps this pose, {@code 1} adopts
     * {@code other} entirely.
     */
    public void blendFrom(@Nonnull final TitanPose other, final float t) {
        if (t <= 0f) return;
        final float clamped = t >= 1f ? 1f : t;
        for (int i = 0; i < localTranslation.length && i < other.localTranslation.length; i++) {
            localTranslation[i].lerp(other.localTranslation[i], clamped);
            localRotation[i].slerp(other.localRotation[i], clamped).normalize();
        }
    }

    /**
     * Folds the local transforms into world matrices.
     *
     * @param rootMatrix the titan's own transform (translation, yaw and uniform scale)
     */
    public void computeWorld(@Nonnull final TitanSkeletonAsset skeleton, @Nonnull final Matrix4d rootMatrix) {
        final var bones = skeleton.getBones();
        for (final int i : skeleton.getEvaluationOrder()) {
            if (i >= world.length) continue;
            final int parent = bones[i].getParentIndex();
            final Matrix4d base = parent >= 0 && parent < world.length ? world[parent] : rootMatrix;

            scratchMatrix.identity()
                .translate(localTranslation[i])
                .rotate(localRotation[i]);
            base.mul(scratchMatrix, world[i]);
        }
    }

    /**
     * Records which bones ended this tick somewhere other than where they ended the last one.
     *
     * <p>Call once the pose is finished. {@link #computeWorld} runs more than once a tick — an IK solver
     * needs world matrices to work from before it can correct them — and only the last of those is the
     * pose anything downstream should be comparing against.
     *
     * <p>The comparison is exact rather than approximate, which sounds too strict to ever match and is
     * the point: bone matrices are built by the same arithmetic from the same inputs, so a bone the
     * animation did not touch this tick lands on bit-identical numbers and can be recognised for free.
     * That covers rather more than idling. Everything a titan does other than walk holds most of its body
     * still — during the Roaming Temple's stomp one leg swings and the other three plus the body do not,
     * so most of its voxels can be left alone. A bone that genuinely is moving slightly is not caught
     * here; that is what the per-part tolerance in the sync system is for.
     */
    public void captureMotion() {
        for (int i = 0; i < world.length; i++) {
            moved[i] = !world[i].equals(settled[i]);
            if (moved[i]) settled[i].set(world[i]);
        }
    }

    /** @see #captureMotion */
    public boolean hasBoneMoved(final int bone) {
        return bone < 0 || bone >= moved.length || moved[bone];
    }

    /** World-space position of a bone's pivot. */
    @Nonnull
    public Vector3d getWorldPosition(final int bone, @Nonnull final Vector3d dest) {
        return world[bone].getTranslation(dest);
    }

    /** World-space position of a point expressed in a bone's local space (model units). */
    @Nonnull
    public Vector3d transformLocal(final int bone, @Nonnull final Vector3d local, @Nonnull final Vector3d dest) {
        return world[bone].transformPosition(local.x, local.y, local.z, dest);
    }

    /**
     * Extracts a bone's world orientation as engine Euler angles.
     *
     * <p>JOML's {@code YXZ} decomposition matches {@link Rotation3f#getQuaternion}, so the value round-trips
     * through {@code TransformComponent} exactly.
     */
    @Nonnull
    public Rotation3f getWorldRotation(final int bone, @Nonnull final Rotation3f dest) {
        world[bone].getNormalizedRotation(scratchQuat);
        scratchQuat.getEulerAnglesYXZ(scratchVec);
        return dest.set((float) scratchVec.x, (float) scratchVec.y, (float) scratchVec.z);
    }

    /** As {@link #getWorldRotation(int, Rotation3f)}, with {@code local} applied on top of the bone. */
    @Nonnull
    public Rotation3f getWorldRotation(final int bone, @Nonnull final Quaterniondc local, @Nonnull final Rotation3f dest) {
        world[bone].getNormalizedRotation(scratchQuat);
        scratchQuat.mul(local).getEulerAnglesYXZ(scratchVec);
        return dest.set((float) scratchVec.x, (float) scratchVec.y, (float) scratchVec.z);
    }

    /**
     * As {@link #getWorldRotation(int, Rotation3f)}, working in scratch the caller owns.
     *
     * <p>Needed because a titan's parts can be synced in parallel, and thousands of threads asking one pose
     * for a bone's orientation cannot share the pose's own scratch. The pose itself is only read here, so
     * with the intermediates handed in this is safe to call from any number of threads at once.
     */
    @Nonnull
    public Rotation3f getWorldRotation(final int bone,
                                       @Nonnull final Rotation3f dest,
                                       @Nonnull final Quaterniond quaternion,
                                       @Nonnull final Vector3d euler) {
        world[bone].getNormalizedRotation(quaternion);
        quaternion.getEulerAnglesYXZ(euler);
        return dest.set((float) euler.x, (float) euler.y, (float) euler.z);
    }
}
