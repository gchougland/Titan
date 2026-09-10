package com.hexvane.titan.crypt;

import com.hexvane.titan.combat.TitanSound;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

/** Finite effects shared by the Crypt Keeper encounter and its reward weapon. */
public final class CryptFx {
    public static final String COFFIN_SMOKE = "Crypt_Coffin_Smoke";
    public static final String COFFIN_STREAM = "Crypt_Coffin_Stream";
    public static final String SOUL_STREAM_CLOSE = "Crypt_Soul_Stream_Close";
    public static final String SOUL_STREAM_END = "Crypt_Soul_Stream_End";
    public static final String EMERGENCE = "Crypt_Emergence";
    public static final String ROAR = "Crypt_Roar";
    public static final String SWEEP_TELEGRAPH = "Crypt_Sweep_Telegraph";
    public static final String SWEEP_IMPACT = "Crypt_Sweep_Impact";
    public static final String SLAM_TELEGRAPH = "Crypt_Slam_Telegraph";
    public static final String SLAM_IMPACT = "Crypt_Slam_Impact";
    public static final String BLUE_FIRE_TELEGRAPH = "Crypt_Blue_Fire_Telegraph";
    public static final String BLUE_FIRE_FLAMES = "Crypt_Blue_Fire_Flames";
    public static final String MINION_SUMMON = "Crypt_Minion_Summon";
    public static final String POISON_TELEGRAPH = "Crypt_Poison_Telegraph";
    public static final String POISON_BREATH = "Crypt_Poison_Breath";
    public static final String POISON_CLOUD = "Crypt_Poison_Cloud";
    public static final String BEAM_TELEGRAPH = "Crypt_Beam_Telegraph";
    public static final String BEAM_CHARGE = "Crypt_Beam_Charge";
    public static final String BEAM = "Crypt_Beam";
    public static final String BRACELET_BREAK = "Crypt_Bracelet_Break";
    public static final String BRACELET_REGEN = "Crypt_Bracelet_Regen";
    public static final String CROWN_HIT = "Crypt_Crown_Hit";
    public static final String DEATH = "Crypt_Death";
    public static final String LOOT_FORM = "Crypt_Loot_Form";
    public static final String LOOT = "Crypt_Loot";
    public static final String STAFF_CAST = "Crypt_Staff_Cast";
    public static final String MISSILE_TRAIL = "Crypt_Missile_Trail";
    public static final String MISSILE_IMPACT = "Crypt_Missile_Impact";
    public static final String GRASP_TELEGRAPH = "Crypt_Grasp_Telegraph";
    public static final String GRASP_IMPACT = "Crypt_Grasp_Impact";
    public static final String STAFF_ORB = "Crypt_Staff_Orb";
    public static final String GRAB_TELEGRAPH = "Crypt_Grab_Telegraph";
    public static final String GRAB_CATCH = "Crypt_Grab_Catch";
    public static final String GRAB_TOSS = "Crypt_Grab_Toss";
    public static final double FIRE_NATIVE_RADIUS = 4.8, POISON_NATIVE_RADIUS = 8.5;
    public static final double SLAM_NATIVE_RADIUS = 6, SWEEP_NATIVE_RADIUS = 5.8, BEAM_NATIVE_RADIUS = 3.2;
    public static final double SOUL_STREAM_RADIUS = 1.1;
    public static final int SOUL_STREAM_STRANDS = 2, SOUL_STREAM_SAMPLES = 7;
    public static final double SOUL_STREAM_NEAR_DISTANCE = 11.8, SOUL_STREAM_GATHER_DISTANCE = 2.8;

    private CryptFx() { }

    public static void burst(ComponentAccessor<EntityStore> accessor, String id, Vector3d at, float scale) {
        if (id == null || id.isBlank() || !valid(at) || !Float.isFinite(scale) || scale <= 0) return;
        ParticleUtil.spawnParticleEffect(id, at, 0, 0, 0, Math.min(32, scale), 0, accessor);
    }

    public static void sound(ComponentAccessor<EntityStore> accessor, String id, Vector3d at) {
        if (valid(at)) TitanSound.play(accessor, id, at);
    }

    /** Caller supplies the dungeon floor height: sampling above it would find the crypt roof. */
    public static void ring(ComponentAccessor<EntityStore> accessor, String id, Vector3d centre, double radius) {
        if (!valid(centre) || !Double.isFinite(radius) || radius <= 0) return;
        burst(accessor, id, new Vector3d(centre).add(0, 0.13, 0), (float) radius);
    }

    /**
     * Radius is the damaging disc radius in blocks. Each native system contains a filled
     * floor and deterministic world-sized emitter tiles, all sent as one particle packet.
     * The agreed boss radii use scale 1; the scale ratio only supports explicit variants.
     */
    public static void area(ComponentAccessor<EntityStore> accessor, String id, Vector3d centre, double radius) {
        if (!valid(centre) || !Double.isFinite(radius) || radius <= 0 || id == null) return;
        double nativeRadius = switch (id) {
            case BLUE_FIRE_FLAMES -> FIRE_NATIVE_RADIUS;
            case POISON_CLOUD -> POISON_NATIVE_RADIUS;
            case SLAM_IMPACT -> SLAM_NATIVE_RADIUS;
            case SWEEP_IMPACT -> SWEEP_NATIVE_RADIUS;
            default -> throw new IllegalArgumentException("No authored filled area for " + id);
        };
        burst(accessor, id, new Vector3d(centre).add(0, .13, 0), (float) (radius / nativeRadius));
    }

    /** Thick overlapping native beam discs follow exactly the supplied damaging segment. */
    public static void beam(ComponentAccessor<EntityStore> accessor, Vector3d from, Vector3d to, double radius) {
        if (!valid(from) || !valid(to) || !Double.isFinite(radius) || radius <= 0) return;
        int steps = Math.max(1, Math.min(64, (int) Math.ceil(from.distance(to) / (radius * .75))));
        Vector3d at = new Vector3d();
        for (int i = 0; i <= steps; i++) {
            from.lerp(to, (double) i / steps, at);
            burst(accessor, BEAM, at, (float) (radius / BEAM_NATIVE_RADIUS));
        }
    }

    /** Fourteen moving smoke samples feed one destination cloud; at most 15 emissions per update. */
    public static void soulStream(ComponentAccessor<EntityStore> accessor, Vector3d from, Vector3d to, double elapsed) {
        if (!valid(from) || !valid(to) || !Double.isFinite(elapsed) || from.distanceSquared(to) < .01) return;
        Vector3d direction = new Vector3d(to).sub(from).normalize();
        Vector3d right = new Vector3d(direction).cross(Math.abs(direction.y) > .96
            ? new Vector3d(1, 0, 0) : new Vector3d(0, 1, 0)).normalize();
        Vector3d up = new Vector3d(right).cross(direction).normalize();
        for (int strand = 0; strand < SOUL_STREAM_STRANDS; strand++) {
            for (int i = 0; i < SOUL_STREAM_SAMPLES; i++) {
                double u = streamProgress(i, strand, elapsed);
                Vector3d at = streamPoint(from, to, right, up, u, elapsed, strand);
                double remaining = at.distance(to);
                if (remaining < SOUL_STREAM_GATHER_DISTANCE) continue;
                Vector3d next = streamPoint(from, to, right, up, Math.min(1, u + .04), elapsed, strand);
                directed(accessor, remaining < SOUL_STREAM_NEAR_DISTANCE ? SOUL_STREAM_CLOSE : COFFIN_STREAM, at, next, 1);
            }
        }
        // Negative radial impulse on these native billboards pulls them to the actual
        // destination in all three axes; no velocity-aligned strips or stationary fog.
        burst(accessor, SOUL_STREAM_END, to, 1);
    }

    static double streamProgress(int sample, int strand, double elapsed) {
        double cycles = elapsed * SOUL_STREAM_SAMPLES * .25;
        double phase = cycles - Math.floor(cycles);
        // Offset alternate billows by half a sample so adjacent clouds overlap,
        // instead of making two separated rows of smoke at the same stations.
        return ((sample + phase + strand * .5) / SOUL_STREAM_SAMPLES) % 1;
    }

    static Vector3d streamPoint(Vector3d from, Vector3d to, Vector3d right, Vector3d up,
                                         double u, double elapsed, int strand) {
        double envelope = Math.sin(Math.PI * u);
        double angle = u * Math.PI * 1.6 - elapsed * .7 + strand * Math.PI
            + .22 * Math.sin(u * Math.PI * 6 + elapsed * 1.2);
        return new Vector3d(from).lerp(to, u)
            .fma(Math.cos(angle) * envelope * SOUL_STREAM_RADIUS, right)
            .fma(Math.sin(angle) * envelope * SOUL_STREAM_RADIUS, up)
            .add(0, envelope * Math.min(3, from.distance(to) * .06), 0);
    }

    /** Overlapping short-lived beam/trail samples, bounded to 48 emissions per call. */
    public static void line(ComponentAccessor<EntityStore> accessor, String id, Vector3d from, Vector3d to, float scale) {
        if (!valid(from) || !valid(to) || !Float.isFinite(scale) || scale <= 0) return;
        double length = from.distance(to);
        int steps = Math.max(1, Math.min(48, (int) Math.ceil(length / Math.max(.35, scale * .7))));
        Vector3d at = new Vector3d();
        for (int i = 0; i <= steps; i++) {
            from.lerp(to, (double) i / steps, at);
            burst(accessor, id, at, scale);
        }
    }

    /** Orients a short breath/cone system from an origin toward a world-space target. */
    public static void directed(ComponentAccessor<EntityStore> accessor, String id, Vector3d from, Vector3d to, float scale) {
        if (!valid(from) || !valid(to) || !Float.isFinite(scale) || scale <= 0) return;
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) Math.atan2(-dx, -dz);
        float pitch = (float) Math.atan2(dy, Math.hypot(dx, dz));
        ParticleUtil.spawnParticleEffect(id, from, yaw, pitch, 0, Math.min(32, scale), 0, accessor);
    }

    private static boolean valid(Vector3d position) {
        return position != null && Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z);
    }
}
