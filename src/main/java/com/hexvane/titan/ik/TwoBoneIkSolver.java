package com.hexvane.titan.ik;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;

/**
 * Analytic two-segment IK: given a shoulder/hip position, two segment lengths and a goal, works out where
 * the elbow/knee has to sit.
 *
 * <p>Arms and legs are always exactly two segments, so the law of cosines gives a closed-form answer. An
 * exact result also keeps the pose stable frame to frame.
 */
public final class TwoBoneIkSolver {

    /** Result of a solve, in world space. */
    public static final class Result {
        @Nonnull
        public final Vector3d jointPosition = new Vector3d();
        @Nonnull
        public final Vector3d endPosition = new Vector3d();
        @Nonnull
        public final Vector3d upperDirection = new Vector3d(0, -1, 0);
        @Nonnull
        public final Vector3d lowerDirection = new Vector3d(0, -1, 0);
        /** {@code true} when the goal was out of reach and the limb had to straighten towards it. */
        public boolean overExtended;

        @Nonnull
        private final Vector3d toGoal = new Vector3d();
        @Nonnull
        private final Vector3d pole = new Vector3d();
    }

    private TwoBoneIkSolver() {
    }

    /**
     * @param root       world position of the shoulder or hip
     * @param goal       world position the effector should reach
     * @param upperLen   length of the first segment
     * @param lowerLen   length of the second segment
     * @param poleTarget world-space hint for which way the joint bends
     */
    public static void solve(@Nonnull final Vector3dc root,
                             @Nonnull final Vector3dc goal,
                             final double upperLen,
                             final double lowerLen,
                             @Nonnull final Vector3dc poleTarget,
                             @Nonnull final Result out) {

        final Vector3d toGoal = out.toGoal.set(goal).sub(root);
        double distance = toGoal.length();

        if (distance < IkMath.EPSILON) {
            // Degenerate goal: fall back to hanging straight down so the limb never collapses into NaN.
            toGoal.set(0, -1, 0);
            distance = IkMath.EPSILON;
        } else {
            toGoal.div(distance);
        }

        final double maxReach = (upperLen + lowerLen) * 0.999;
        final double minReach = Math.abs(upperLen - lowerLen) * 1.001;
        final double clamped = Math.max(minReach, Math.min(maxReach, distance));
        out.overExtended = distance > maxReach;

        final Vector3d pole = out.pole;
        if (!IkMath.perpendicular(poleTarget, toGoal, pole)) {
            IkMath.anyPerpendicular(toGoal, pole);
        }

        final double cosAlpha = Math.max(-1.0, Math.min(1.0,
            (upperLen * upperLen + clamped * clamped - lowerLen * lowerLen) / (2.0 * upperLen * clamped)));
        final double alpha = Math.acos(cosAlpha);

        out.upperDirection
            .set(toGoal).mul(Math.cos(alpha))
            .fma(Math.sin(alpha), pole)
            .normalize();

        out.jointPosition.set(root).fma(upperLen, out.upperDirection);
        out.endPosition.set(root).fma(clamped, toGoal);

        out.lowerDirection.set(out.endPosition).sub(out.jointPosition);
        if (out.lowerDirection.lengthSquared() < IkMath.EPSILON) {
            out.lowerDirection.set(toGoal);
        } else {
            out.lowerDirection.normalize();
        }
    }
}
