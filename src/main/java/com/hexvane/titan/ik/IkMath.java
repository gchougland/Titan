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
     * Builds the world rotation that points a bone's local axis along {@code worldDir}, twisted so the
     * bone's bend reference faces {@code worldPole}.
     *
     * <p>Without the twist term a swing-only solve leaves elbows and knees free to spin around the limb,
     * which reads as the leg popping between frames.
     */
    public static void alignAxis(@Nonnull final Quaterniond dest,
                                 @Nonnull final Vector3dc localAxis,
                                 @Nonnull final Vector3dc worldDir,
                                 @Nonnull final Vector3dc worldPole,
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

        if (!perpendicular(worldPole, u, scratch.poleP)) return;

        anyPerpendicular(a, scratch.bend);
        dest.transform(scratch.bend);
        if (!perpendicular(scratch.bend, u, scratch.bend)) return;

        final double cos = Math.max(-1.0, Math.min(1.0, scratch.bend.dot(scratch.poleP)));
        final double sin = scratch.cross.set(scratch.bend).cross(scratch.poleP).dot(u);
        final double angle = Math.atan2(sin, cos);

        dest.premul(scratch.twist.identity().fromAxisAngleRad(u.x, u.y, u.z, angle));
    }
}
