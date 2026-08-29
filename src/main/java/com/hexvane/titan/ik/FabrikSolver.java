package com.hexvane.titan.ik;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;

/**
 * Forward And Backward Reaching Inverse Kinematics for chains longer than two segments, such as a tail or
 * a segmented tentacle arm. Arms and legs use {@link TwoBoneIkSolver} instead.
 */
public final class FabrikSolver {

    private static final int DEFAULT_ITERATIONS = 12;
    private static final double DEFAULT_TOLERANCE = 0.01;

    private FabrikSolver() {
    }

    /**
     * Moves {@code joints} so the last one reaches {@code goal} while preserving the distances between
     * consecutive joints. {@code joints[0]} is pinned.
     *
     * @param joints  world positions, modified in place; at least two entries
     * @param lengths fixed distance between {@code joints[i]} and {@code joints[i + 1]}
     * @param count   how many leading entries of {@code joints} take part, letting callers reuse
     *                oversized scratch arrays
     */
    public static void solve(@Nonnull final Vector3d[] joints,
                             @Nonnull final double[] lengths,
                             @Nonnull final Vector3dc goal,
                             final int count) {
        solve(joints, lengths, goal, count, DEFAULT_ITERATIONS, DEFAULT_TOLERANCE);
    }

    public static void solve(@Nonnull final Vector3d[] joints,
                             @Nonnull final double[] lengths,
                             @Nonnull final Vector3dc goal,
                             final int count,
                             final int iterations,
                             final double tolerance) {

        if (count < 2 || joints.length < count || lengths.length < count - 1) return;

        final double originX = joints[0].x;
        final double originY = joints[0].y;
        final double originZ = joints[0].z;

        double total = 0;
        for (int i = 0; i < count - 1; i++) {
            total += lengths[i];
        }

        // Goal beyond reach: stretch straight at it in one pass, no iteration needed.
        final double dx = goal.x() - originX;
        final double dy = goal.y() - originY;
        final double dz = goal.z() - originZ;
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > total) {
            if (distance < IkMath.EPSILON) return;
            final double nx = dx / distance;
            final double ny = dy / distance;
            final double nz = dz / distance;
            for (int i = 1; i < count; i++) {
                joints[i].set(
                    joints[i - 1].x + nx * lengths[i - 1],
                    joints[i - 1].y + ny * lengths[i - 1],
                    joints[i - 1].z + nz * lengths[i - 1]);
            }
            return;
        }

        for (int iteration = 0; iteration < iterations; iteration++) {
            if (joints[count - 1].distance(goal) <= tolerance) break;

            joints[count - 1].set(goal);
            for (int i = count - 2; i >= 0; i--) {
                dragTowards(joints[i], joints[i + 1], lengths[i]);
            }

            joints[0].set(originX, originY, originZ);
            for (int i = 1; i < count; i++) {
                dragTowards(joints[i], joints[i - 1], lengths[i - 1]);
            }
        }
    }

    /** Places {@code point} at exactly {@code length} away from {@code anchor}, along the existing direction. */
    private static void dragTowards(@Nonnull final Vector3d point, @Nonnull final Vector3d anchor, final double length) {
        final double dx = point.x - anchor.x;
        final double dy = point.y - anchor.y;
        final double dz = point.z - anchor.z;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < IkMath.EPSILON) {
            point.set(anchor.x, anchor.y - length, anchor.z);
            return;
        }
        final double s = length / len;
        point.set(anchor.x + dx * s, anchor.y + dy * s, anchor.z + dz * s);
    }
}
