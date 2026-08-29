package com.hexvane.titan.ik;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;

/**
 * Shared vector helpers for the IK solvers.
 */
public final class IkMath {

    public static final double EPSILON = 1.0e-6;

    /** Squared sine of 15 degrees: below this a bone counts as vertical for {@link #uprightTwist}. */
    private static final double UPRIGHT_MIN_LENGTH_SQ = 0.067;

    private IkMath() {
    }

    /**
     * Removes the component of {@code v} that lies along {@code axis} and normalises what is left.
     *
     * @return {@code false} when {@code v} is parallel to {@code axis}, leaving {@code dest} untouched
     */
    public static boolean perpendicular(@Nonnull final Vector3dc v, @Nonnull final Vector3dc axis, @Nonnull final Vector3d dest) {
        final double dot = v.dot(axis);
        dest.set(v.x() - axis.x() * dot, v.y() - axis.y() * dot, v.z() - axis.z() * dot);
        final double len = dest.length();
        if (len < EPSILON) return false;
        dest.div(len);
        return true;
    }

    /** Any unit vector orthogonal to {@code axis}. */
    @Nonnull
    public static Vector3d anyPerpendicular(@Nonnull final Vector3dc axis, @Nonnull final Vector3d dest) {
        // Seeding with the axis furthest from `axis` keeps the projection well conditioned.
        if (Math.abs(axis.y()) < 0.9) {
            dest.set(-axis.x() * axis.y(), 1 - axis.y() * axis.y(), -axis.z() * axis.y());
        } else {
            dest.set(1 - axis.x() * axis.x(), -axis.y() * axis.x(), -axis.z() * axis.x());
        }
        final double len = dest.length();
        if (len < EPSILON) {
            dest.set(1, 0, 0);
        } else {
            dest.div(len);
        }
        return dest;
    }

    /** Reusable temporaries so a full IK pass allocates nothing. */
    public static final class Scratch {
        @Nonnull
        final Vector3d a = new Vector3d();
        @Nonnull
        final Vector3d u = new Vector3d();
        @Nonnull
        final Vector3d poleP = new Vector3d();
        @Nonnull
        final Vector3d bend = new Vector3d();
        @Nonnull
        final Vector3d cross = new Vector3d();
        @Nonnull
        final Quaterniond twist = new Quaterniond();
    }

    /**
     * Reference direction for rolling a bone about its own axis so that a flat face of its square
     * cross-section ends up on top, rather than an edge.
     *
     * <p>The bend pole is the wrong reference for this. It lies in the plane the joint bends through, so on
     * a limb held out at a slant the projected pole tips well away from horizontal and drags the
     * cross-section round with it, leaving a ridge where the player wants a walkable face. World up
     * projects cleanly out of any limb that is not close to vertical.
     *
     * @param pole fallback for a near-vertical bone, where up carries no roll information and normalising
     *             the little that is left of it would make the bone spin on the spot
     */
    @Nonnull
    public static Vector3d uprightTwist(@Nonnull final Vector3dc direction,
                                        @Nonnull final Vector3dc pole,
                                        @Nonnull final Vector3d dest) {
        // World up with the component along the bone removed. Its length is the sine of the bone's angle
        // off vertical, which is exactly the quantity the fallback threshold is about.
        final double along = direction.y();
        dest.set(-direction.x() * along, 1 - along * along, -direction.z() * along);
        if (dest.lengthSquared() < UPRIGHT_MIN_LENGTH_SQ) return dest.set(pole);
        return dest.normalize();
    }

    /**
     * Builds the world rotation that points a bone's local axis along {@code worldDir}, rolled about that
     * axis so the bone's reference side faces {@code worldTwist}.
     *
     * <p>Without the twist term a swing-only solve leaves elbows and knees free to spin around the limb,
     * which reads as the leg popping between frames.
     */
    public static void alignAxis(@Nonnull final Quaterniond dest,
                                 @Nonnull final Vector3dc localAxis,
                                 @Nonnull final Vector3dc worldDir,
                                 @Nonnull final Vector3dc worldTwist,
                                 @Nonnull final Scratch scratch) {
        final Vector3d a = scratch.a.set(localAxis);
        final Vector3d u = scratch.u.set(worldDir);
        if (a.lengthSquared() < EPSILON || u.lengthSquared() < EPSILON) {
            dest.identity();
            return;
        }
        a.normalize();
        u.normalize();

        dest.identity().rotationTo(a, u);

        if (!perpendicular(worldTwist, u, scratch.poleP)) return;

        anyPerpendicular(a, scratch.bend);
        dest.transform(scratch.bend);
        if (!perpendicular(scratch.bend, u, scratch.bend)) return;

        final double cos = Math.max(-1.0, Math.min(1.0, scratch.bend.dot(scratch.poleP)));
        final double sin = scratch.cross.set(scratch.bend).cross(scratch.poleP).dot(u);
        final double angle = Math.atan2(sin, cos);

        dest.premul(scratch.twist.identity().fromAxisAngleRad(u.x, u.y, u.z, angle));
    }
}
