package com.hexvane.titan.spawn;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Turns a block's stored orientation into something an entity transform can carry.
 *
 * <p>A slab, stair or pillar keeps its facing in a {@code RotationTuple} index alongside its block id, which
 * the chunk renderer reads directly. {@code BlockEntity} carries only the block key, so for a titan's blocks
 * the orientation has to be folded into the entity's rotation on top of whatever the bone is doing.
 */
public final class BlockRotations {

    /**
     * A block's mesh faces the opposite way as an entity to the way it faces in a chunk, so every rotation
     * carries half a turn of yaw to cancel that out. The engine applies the same correction in
     * {@code FallingBlock} and {@code CarriedBlock}, both of which add {@code PI} to the yaw they hand the
     * transform. It applies to unrotated blocks too, so there is no fast path for them.
     */
    private static final float ENTITY_YAW_OFFSET = (float) Math.PI;

    /**
     * One quaternion per rotation index, built once. There are only 64 and they never change, so no block
     * has to rebuild one from Euler angles per tick.
     */
    @Nonnull
    private static final Quaterniond[] QUATERNIONS = build();

    private BlockRotations() {
    }

    @Nonnull
    private static Quaterniond[] build() {
        final var table = new Quaterniond[RotationTuple.VALUES.length];
        for (int i = 0; i < table.length; i++) {
            final RotationTuple tuple = RotationTuple.VALUES[i] == null ? RotationTuple.NONE : RotationTuple.VALUES[i];
            final var quaternion = new Quaterniond();
            new Rotation3f(
                (float) tuple.pitch().getRadians(),
                (float) tuple.yaw().getRadians() + ENTITY_YAW_OFFSET,
                (float) tuple.roll().getRadians()
            ).getQuaternion(quaternion);
            table[i] = quaternion;
        }
        return table;
    }

    /**
     * Folds a block's own orientation into {@code boneRotation}, in place.
     *
     * <p>Composed as {@code bone * block} so the block turns within its bone: a stair on a leg keeps facing
     * along that leg as it swings instead of being pinned to a world direction. The scratch objects belong
     * to the caller because this runs for every voxel of a moving titan and JOML's {@code mul} overloads
     * allocate their intermediates.
     */
    public static void compose(@Nonnull final Rotation3f boneRotation,
                               final int rotationIndex,
                               @Nonnull final Quaterniond scratchQuaternion,
                               @Nonnull final Vector3d scratchEuler) {

        final int index = rotationIndex >= 0 && rotationIndex < QUATERNIONS.length
            ? rotationIndex
            : RotationTuple.NONE_INDEX;

        boneRotation.getQuaternion(scratchQuaternion)
            .mul(QUATERNIONS[index])
            .getEulerAnglesYXZ(scratchEuler);

        boneRotation.set((float) scratchEuler.x, (float) scratchEuler.y, (float) scratchEuler.z);
    }
}
