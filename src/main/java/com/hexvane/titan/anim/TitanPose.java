package com.hexvane.titan.anim;

import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
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
        for (int i = 0; i < boneCount; i++) {
            localTranslation[i] = new Vector3d();
            localRotation[i] = new Quaterniond();
            world[i] = new Matrix4d();
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
}
