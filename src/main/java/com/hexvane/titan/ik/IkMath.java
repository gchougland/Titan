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

    /** Squared sine of 15 degrees: inside this a bone counts as upright for {@link #uprightTwist}. */
    private static final double UPRIGHT_BLEND_SIN_SQ = 0.067;
    private static final double UPRIGHT_BLEND_SIN = Math.sqrt(UPRIGHT_BLEND_SIN_SQ);

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
     * <p>Close to vertical it has to hand over to the pole regardless, and the handover is eased rather
     * than switched. The two references can point opposite ways, so switching between them was rolling a
     * bone half a turn in a single tick — see the comment on the sign below, which is the substance of it.
     *
     * <p>One discontinuity is left, at dead vertical, and it cannot be removed. Projected up is the uphill
     * direction across the bone, and uphill reverses as a bone tips through vertical, so anything that
     * keeps a face uphill has to roll half a turn on the way through. Only the sign of an undefined
     * quantity, in a pose where the roll it decides is unobservable on a bone pointing at the sky. Limbs do
     * not sit there in practice: the temple's shin is between 10 and 19 degrees off vertical through its
     * whole gait, because the knee bending outward is what holds the shin off vertical in the first place.
     * {@code tools/_twist_continuity.py} sweeps both versions and prints the worst jump in each.
     *
     * @param pole stands in for up as a bone approaches vertical, where up carries no roll information and
     *             normalising the little that is left of it would make the bone spin on the spot
     */
    @Nonnull
    public static Vector3d uprightTwist(@Nonnull final Vector3dc direction,
                                        @Nonnull final Vector3dc pole,
                                        @Nonnull final Vector3d dest) {
        // World up with the component along the bone removed. Its length is the sine of the bone's angle
        // off vertical, which is exactly the quantity the handover to the pole is about.
        final double along = direction.y();
        dest.set(-direction.x() * along, 1 - along * along, -direction.z() * along);

        final double sinSq = dest.lengthSquared();
        if (sinSq >= UPRIGHT_BLEND_SIN_SQ) return dest.normalize();

        // Which end of the pole axis to lean on, decided by the side up was already pointing.
        //
        // This is the whole of the fix for a titan's legs snapping half a turn round as they walk. The two
        // references do not merely differ near the handover, they oppose each other: work through a bone
        // leaning away from its own pole, which is exactly what a shin does when the knee bends outward,
        // and projected up comes out as (-cos, sin, 0) against the pole's (cos, -sin, 0). Precisely
        // antiparallel. Swapping between them therefore rolled the bone by exactly 180 degrees, and the
        // temple's shin sits at 18 degrees off vertical standing and 10 mid-stride, so it crossed the
        // threshold twice a step and rolled a half turn each time. On a square stone column that is
        // invisible; on a column with a crystal growing out of one face it is the crystal changing sides.
        final double side = dest.dot(pole) < 0 ? -1 : 1;

        // Eased rather than mixed straight so the two branches meet with matching slope as well as matching
        // value, leaving no rate of roll for the eye to catch at the join.
        final double s = Math.sqrt(sinSq) / UPRIGHT_BLEND_SIN;
        final double t = s * s * (3 - 2 * s);

        if (sinSq > EPSILON * EPSILON) {
            dest.mul(t / Math.sqrt(sinSq));
        } else {
            dest.set(0, 0, 0);
        }

        final double weight = (1 - t) * side;
        dest.add(pole.x() * weight, pole.y() * weight, pole.z() * weight);

        // Cannot cancel out: the sign above leaves the two at a non-negative dot, so the shortest the sum
        // gets is when they are square to each other, and even that keeps most of a unit of length.
        final double len = dest.length();
        return len < EPSILON ? dest.set(pole) : dest.div(len);
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
