package com.hexvane.titan.crypt;

import org.joml.Vector3d;

/** Spell timeline and target rules kept independent of ECS for repeatable encounter checks. */
final class CryptStaffRules {
    private CryptStaffRules() {}
    static final double MISSILE_SPEED = 7, MISSILE_RADIUS = .22;

    /** Close targets cannot be orbited or overshot while waiting for a slow steering blend. */
    static void steer(Vector3d velocity, Vector3d delta, double dt) {
        double distance = delta.length();
        if (distance < 1e-7) return;
        var desired = new Vector3d(delta).mul(MISSILE_SPEED / distance);
        if (distance <= 3) velocity.set(desired);
        else {
            velocity.lerp(desired, 1 - Math.exp(-(6 + 12 / distance) * Math.max(0, dt)));
            if (velocity.lengthSquared() < 1e-8) velocity.set(desired);
            else velocity.normalize().mul(MISSILE_SPEED);
        }
    }

    /** Earliest intersection with a body expanded by the soul's radius; includes launch overlap. */
    static double bodyIntersection(Vector3d from, Vector3d to, Vector3d min, Vector3d max, double radius) {
        double near = 0, far = 1;
        for (int axis = 0; axis < 3; axis++) {
            double start = from.get(axis), delta = to.get(axis) - start;
            double low = min.get(axis) - radius, high = max.get(axis) + radius;
            if (Math.abs(delta) < 1e-10) {
                if (start < low || start > high) return Double.POSITIVE_INFINITY;
            } else {
                double a = (low - start) / delta, b = (high - start) / delta;
                near = Math.max(near, Math.min(a, b));far = Math.min(far, Math.max(a, b));
                if (near > far) return Double.POSITIVE_INFINITY;
            }
        }
        return near;
    }
    static boolean canTargetPool(CryptFight fight, int pool) {
        if (fight == null || !fight.damageable() || pool < 0 || pool > 2) return false;
        return pool == 0 || (fight.bracelet(pool - 1) > 0 && fight.state() != CryptFight.State.RECOVERING);
    }
    static double armHeight(double time) {
        if (time < 1.15) return 7 + Math.sin(time * Math.PI / 1.15) * 0.12;
        if (time < 1.48) return 7 * (1 - Math.pow((time - 1.15) / .33, 2));
        if (time < 1.85) return 0;
        return Math.pow((time - 1.85) / .95, 2) * 10;
    }
}
