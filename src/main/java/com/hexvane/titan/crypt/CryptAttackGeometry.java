package com.hexvane.titan.crypt;

/** Shared room-space trajectories keep visible windup warnings aligned with the moving hands. */
public final class CryptAttackGeometry {
    public static final double SWEEP_DAMAGE_RADIUS = 5.8;
    public static final double SWEEP_WARNING_RADIUS = 6.6;
    public static final int SWEEP_WARNING_SEGMENTS = 16;
    public static final double SLAM_RADIUS = 6;
    public static final double FIRE_RADIUS = 4.8;
    public static final double POISON_RADIUS = 8.5;
    public static final double BEAM_DAMAGE_RADIUS = 3.2;
    public static final double GRAB_RADIUS = 4.2;
    public static final double AIM_LOCK_SECONDS = .75;

    private CryptAttackGeometry() { }

    public static double sweepRight(double progress, boolean fromLeft) {
        double t = Math.clamp(progress, 0, 1);
        return (fromLeft ? -1 : 1) * (31 - 62 * t * t * (3 - 2 * t));
    }

    public static double sweepForward(double progress) {
        return 24 + 5 * Math.sin(Math.clamp(progress, 0, 1) * Math.PI);
    }

    /** Sweep the lane the target occupies, including players close to the pit or near the rear wall. */
    public static double sweepOffset(double targetForward) { return Math.clamp(targetForward - 26.5, -10, 18); }

    public static org.joml.Vector3d beamEnd(CryptBossComponent b) {
        double progress = Math.clamp((b.fight.time()-b.fight.windup())/b.fight.activeDuration(),0,1);
        return b.arena.point((b.beamLeft ? -1 : 1)*(31-62*progress),1.3,51);
    }

    /** Swept horizontal capsule: a slow server frame cannot leave holes between hand hit samples. */
    public static boolean sweptHandHit(org.joml.Vector3d player, org.joml.Vector3d from,
                                       org.joml.Vector3d to, double radius, double height) {
        double dx=to.x-from.x, dz=to.z-from.z, length=dx*dx+dz*dz;
        double t=length<1e-8 ? 0 : Math.clamp(((player.x-from.x)*dx+(player.z-from.z)*dz)/length,0,1);
        double x=player.x-from.x-t*dx, z=player.z-from.z-t*dz;
        return x*x+z*z<=radius*radius && Math.abs(player.y-(from.y+(to.y-from.y)*t))<=height;
    }
}
