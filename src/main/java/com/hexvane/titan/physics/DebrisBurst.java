package com.hexvane.titan.physics;

import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the velocities handed to {@code TitanPartComponent.detach} when a body comes apart.
 *
 * <p>Used both by a dying titan shedding its voxels and by a boulder shattering on impact. The two differ
 * only in their constants and in whether the boulder's own momentum is folded in afterwards, so the caller
 * keeps its own tuning and this only owns the shape of the burst.
 */
public final class DebrisBurst {

    /** Offsets shorter than this have no usable direction, so the piece is thrown straight up instead. */
    private static final double MIN_OFFSET = 1.0e-3;

    private DebrisBurst() {
    }

    /**
     * Aims one piece of debris away from the centre of the burst.
     *
     * @param offset from the centre of the burst to the piece
     * @param speed  outward speed, in blocks per second. Pass a value proportional to the offset length to
     *               make the extremities fly furthest, or a constant to scatter every piece equally.
     * @param lift   upward speed added on top of the outward component
     * @param dest   receives the result, and may alias {@code offset}
     * @return {@code dest}
     */
    @Nonnull
    public static Vector3d solve(@Nonnull final Vector3d offset,
                                 final double speed,
                                 final double lift,
                                 @Nonnull final Vector3d dest) {

        dest.set(offset);
        final double distance = dest.length();
        if (distance < MIN_OFFSET) {
            dest.set(0, lift, 0);
            return dest;
        }

        dest.div(distance).mul(speed);
        // Never thrown downwards, since debris driven into the floor just clips through it.
        dest.y = Math.max(dest.y, 0) + lift;
        return dest;
    }

    /**
     * Rolls a tumble for one piece of debris.
     *
     * @param magnitude largest spin on any one axis, in radians per second
     * @return {@code dest}
     */
    @Nonnull
    public static Vector3d spin(@Nonnull final ThreadLocalRandom random,
                                final double magnitude,
                                @Nonnull final Vector3d dest) {
        return dest.set(
            random.nextDouble(-magnitude, magnitude),
            random.nextDouble(-magnitude, magnitude),
            random.nextDouble(-magnitude, magnitude)
        );
    }
}
