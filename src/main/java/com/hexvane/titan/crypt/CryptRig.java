package com.hexvane.titan.crypt;

import java.util.*;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Articulated floating skull and two mirrored arms. Only fore/upper arms and palms are platforms. */
public final class CryptRig {
    static final double CROWN_ATTACHMENT_HEIGHT = 2.5;
    static final double JAW_ATTACHMENT_HEIGHT = -2.3;
    static final double JAW_ATTACHMENT_DEPTH = 3;
    static final double SHOULDER_HEIGHT = 9;
    public static final class Bone {
        public final String name, prefab;
        public final Vector3d pivot, position = new Vector3d();
        public final Quaterniond rotation = new Quaterniond();
        public final boolean mirrored, platform;
        public final int pool;
        public double lengthScale = 1;
        Bone(String name, String prefab, Vector3d pivot, boolean mirrored, boolean platform, int pool) {
            this.name = name; this.prefab = "Titan/Crypt/Skeleton_" + prefab + ".prefab.json";
            this.pivot = pivot; this.mirrored = mirrored; this.platform = platform; this.pool = pool;
        }
    }
    public final List<Bone> bones = new ArrayList<>();
    private final Bone head, jaw, crown;
    private final Bone[][] arms = new Bone[2][4];
    private final Bone[][][] digits = new Bone[2][4][3];
    private final Quaterniond basis;
    private final Quaterniond trackedLook = new Quaterniond();
    private double lastSampleElapsed = Double.NaN;
    private record Pose(Vector3d position, Quaterniond rotation, double lengthScale) { }
    private List<Pose> deathStart;
    public CryptRig(CryptArena arena) {
        Vector3d right = arena.point(1, 0, 0).sub(arena.point(0, 0, 0));
        basis = new Quaterniond().rotationY(Math.atan2(-right.z, right.x));
        head = add("Head", "Head", new Vector3d(.5, 6.5, -.5), false, false, -1);
        jaw = add("Jaw", "Jaw", new Vector3d(.5, 8, 5.5), false, false, -1);
        crown = add("Crown", "Crown", new Vector3d(.5, 0, .5), false, false, 0);
        for (int side = 0; side < 2; side++) {
            String prefix = side == 0 ? "L" : "R";
            arms[side][0] = add(prefix + "UpperArm", "Arm", new Vector3d(1, 1.5, .5), side == 1, true, -1);
            arms[side][1] = add(prefix + "Forearm", "Arm", new Vector3d(1, 1.5, .5), side == 1, true, -1);
            arms[side][2] = add(prefix + "Palm", "Palm", new Vector3d(.5, 1.5, .5), side == 1, true, -1);
            arms[side][3] = add(prefix + "Bracelet", "Bracelet", new Vector3d(1, 3, .5), side == 1, false, side + 1);
            for (int finger = 0; finger < 4; finger++) for (int joint = 0; joint < 3; joint++) {
                digits[side][finger][joint] = add(prefix + (finger == 3 ? "Thumb" : "Finger" + finger) + "_" + joint,
                    "Finger", new Vector3d(.5, .5, 0), side == 1, false, -1);
            }
        }
    }
    private Bone add(String name, String prefab, Vector3d pivot, boolean mirror, boolean platform, int pool) {
        Bone b = new Bone(name, prefab, pivot, mirror, platform, pool); bones.add(b); return b;
    }
    public void sample(CryptBossComponent boss) {
        CryptFight f = boss.fight;
        double dt = Double.isFinite(lastSampleElapsed)
            ? Math.clamp(boss.elapsed - lastSampleElapsed, 0, .25) : 0;
        lastSampleElapsed = boss.elapsed;
        if (f.state() == CryptFight.State.DYING && deathStart == null) {
            // A finishing blow usually lands while the skull is on the floor. Rise from that
            // exact pose into the death performance instead of teleporting back above the pit.
            deathStart = bones.stream().map(b -> new Pose(new Vector3d(b.position),
                new Quaterniond(b.rotation), b.lengthScale)).toList();
        }
        double t = f.time(), bob = Math.sin(boss.elapsed * 1.4) * .24;
        Vector3d skull = new Vector3d(0, 11.5 + bob, 0);
        Quaterniond skullQ = new Quaterniond().rotateY(Math.sin(boss.elapsed * .45) * .055);
        double jawOpen = .045 + Math.sin(boss.elapsed * 1.2) * .02;
        Vector3d[] palms = { new Vector3d(-14, 3.5 + bob, -13), new Vector3d(14, 3.5 - bob, -13) };
        double[] curls = {.3, .3};
        double bodyDrop = 0;
        double summonFlip = 0, summonRaise = 0;
        double beamAimWeight = 0;
        double introRelease = CryptFight.smooth((t - (CryptFight.INTRO_SECONDS - 1.4)) / 1.4);
        if (f.state() == CryptFight.State.INTRO) {
            double rise = CryptFight.smooth((t - 6.5) / 4);
            bodyDrop = -29 * (1 - rise);
            skull.y += bodyDrop;
            for (int s = 0; s < 2; s++) {
                Vector3d idlePalm = new Vector3d(palms[s]);
                Vector3d grip = local(boss.arena, boss.arena.grabs().get(s));
                double reach = CryptFight.smooth((t - (s == 0 ? 2.8 : 4.7)) / 2.0);
                palms[s].set(grip.x, grip.y + 2.2, -grip.z);
                palms[s].y -= 20 * (1 - reach);
                curls[s] = .15 + .85 * CryptFight.smooth((t - (s == 0 ? 4.5 : 6.4)) / .65);
                // Release the pit lip as control returns, without snapping position or finger joints.
                palms[s].lerp(idlePalm, introRelease);
                curls[s] += (.3 - curls[s]) * introRelease;
            }
            jawOpen = .05 + .7 * Math.sin(Math.PI * Math.clamp((t - 10.5) / 3, 0, 1));
            skullQ.rotateX(-jawOpen * .16);
        } else if (f.state() == CryptFight.State.FALLING || f.state() == CryptFight.State.STUNNED || f.state() == CryptFight.State.RECOVERING) {
            double fall = f.state() == CryptFight.State.FALLING ? CryptFight.smooth(t / 2.2)
                : f.state() == CryptFight.State.RECOVERING ? 1 - CryptFight.smooth(t / 3) : 1;
            skull.lerp(new Vector3d(6.7, 6.6, -24), fall);
            skullQ.identity().rotateZ(Math.PI * .5 * fall).rotateX(.07 * fall);
            jawOpen = .42 * fall;
            for (int s = 0; s < 2; s++) {
                palms[s].lerp(new Vector3d(s == 0 ? -12 : 18, 1.5, -20), fall);
                curls[s] = .15;
            }
        } else if (f.state() == CryptFight.State.ATTACK) {
            double wind = CryptFight.smooth(t / f.windup());
            double active = Math.clamp((t - f.windup()) / f.activeDuration(), 0, 1);
            double recovery = 1 - CryptFight.smooth((t - f.windup() - f.activeDuration()) / 1.3);
            switch (f.move()) {
                case SWEEP_LEFT, SWEEP_RIGHT -> {
                    int s = f.move() == CryptFight.Move.SWEEP_LEFT ? 0 : 1;
                    double sign = s == 0 ? -1 : 1;
                    palms[s].lerp(new Vector3d(CryptAttackGeometry.sweepRight(active, s == 0), 2.1,
                        -(CryptAttackGeometry.sweepForward(active) + boss.sweepOffset)), wind * recovery);
                    curls[s] = .38; skullQ.rotateY(-sign * .18 * wind * recovery);
                }
                case SLAM -> {
                    Vector3d aim = local(boss.arena, boss.target);
                    int s = boss.beamLeft ? 0 : 1;
                    double height = 11 - 9 * CryptFight.smooth(active);
                    palms[s].lerp(new Vector3d(aim.x, aim.y + height, -aim.z), wind * recovery);
                    curls[s] = 1.3 * wind * recovery;
                }
                case GRAB -> {
                    Vector3d aim = local(boss.arena, boss.target);
                    int s = boss.grabSide;
                    double seconds = t-f.windup();
                    double lift = CryptFight.smooth((seconds-CryptGrab.CATCH_TIME)/.85);
                    double toss = CryptFight.smooth((seconds-1.35)/.45);
                    Vector3d grasp = new Vector3d(aim.x*(1-.25*toss),
                        aim.y-2+6*lift-1.5*toss, -aim.z+(-37+aim.z)*.25*toss);
                    palms[s].lerp(grasp,wind*recovery);
                    double close = CryptFight.smooth(seconds/CryptGrab.CATCH_TIME);
                    curls[s] += (.08+1.15*close*(1-toss)-curls[s])*wind*recovery;
                    jawOpen = .32*wind*recovery;
                }
                case MINIONS, BLUE_FIRE -> {
                    // Turn the wrists over before lifting the hands. Fingers keep their
                    // normal closing direction instead of bending backward to point up.
                    summonFlip = CryptFight.smooth(t / (f.windup() * .45)) * recovery;
                    summonRaise = CryptFight.smooth((t - f.windup() * .3) / (f.windup() * .7)) * recovery;
                    for (int s = 0; s < 2; s++) {
                        palms[s].y += 7 * summonRaise;
                        curls[s] += (.1 - curls[s]) * summonRaise;
                    }
                    jawOpen = .35 * wind * recovery;
                }
                case POISON -> {
                    skull.z -= 4 * wind * recovery; skullQ.rotateX(.18 * wind * recovery);
                    jawOpen = .7 * wind * recovery;
                }
                case BEAM -> {
                    beamAimWeight = wind * recovery;
                    skull.y -= 3.5 * beamAimWeight;
                    jawOpen = .85 * wind * recovery;
                }
            }
        } else if (f.state() == CryptFight.State.DYING || f.state() == CryptFight.State.FINISHED) {
            double collapse = CryptFight.smooth((t - 2.5) / 6);
            skull.y += 2 * Math.sin(Math.min(t / 2.5, 1) * Math.PI) - 28 * collapse;
            skullQ.rotateZ(Math.sin(t * 16) * .03 + collapse * .7).rotateX(-collapse * .4);
            jawOpen = .9 * (1 - collapse);
            for (int s = 0; s < 2; s++) { palms[s].y -= 18 * CryptFight.smooth((t - 3 - s * .5) / 4); curls[s] = .2; }
            bodyDrop = -24 * collapse;
        }
        if (f.state() == CryptFight.State.REST || f.state() == CryptFight.State.ATTACK) {
            Quaterniond desired = boss.hasLookTarget
                ? lookRotation(boss.arena, skull, boss.lookTarget, false) : new Quaterniond();
            trackedLook.slerp(desired, 1 - Math.exp(-5.5 * dt));
            skullQ.premul(trackedLook);
            if (beamAimWeight > 0) {
                // Aim from the mouth, whose origin is below the skull pivot. During the
                // active sweep the visible face and damage ray share the same endpoint.
                skullQ.slerp(lookRotation(boss.arena, skull, boss.beamAim, true), beamAimWeight);
            }
        } else {
            // Cinematics and the crown vulnerability pose retain their authored rotations.
            trackedLook.identity();
        }
        put(boss.arena, head, skull, skullQ);
        attach(boss.arena, crown, skull, skullQ, new Vector3d(0, CROWN_ATTACHMENT_HEIGHT, 0), new Quaterniond());
        // The face points along local -Z, so reducing depth moves the entire jaw forward.
        attach(boss.arena, jaw, skull, skullQ, new Vector3d(0, JAW_ATTACHMENT_HEIGHT, JAW_ATTACHMENT_DEPTH), new Quaterniond().rotateX(-jawOpen));
        for (int s = 0; s < 2; s++) {
            double sign = s == 0 ? -1 : 1;
            Vector3d shoulder = new Vector3d(sign * 9, SHOULDER_HEIGHT + bodyDrop, 4);
            Vector3d elbow = new Vector3d(sign * 19, Math.max(-16, palms[s].y * .45 + 4 + bodyDrop * .45),
                palms[s].z * .35 + 2);
            Quaterniond palmQ = new Quaterniond();
            if (f.state() == CryptFight.State.INTRO) palmQ.rotateY(sign * Math.PI / 2 * (1 - introRelease));
            palmQ.rotateX(Math.PI * .5 * summonRaise).rotateZ(sign * Math.PI * summonFlip);
            if (f.state() == CryptFight.State.ATTACK && f.move() == CryptFight.Move.SLAM) {
                int slamSide = boss.beamLeft ? 0 : 1;
                if (s == slamSide) palmQ.rotateX(-.35 * CryptFight.smooth(t / f.windup()));
            }
            double grabFlip = f.state() == CryptFight.State.ATTACK && f.move() == CryptFight.Move.GRAB && s == boss.grabSide
                ? CryptFight.smooth(t/f.windup())*(1-CryptFight.smooth((t-f.windup()-f.activeDuration())/1.3)) : 0;
            palmQ.rotateZ(sign*Math.PI*grabFlip);
            segment(boss.arena, arms[s][0], shoulder, elbow, sign * Math.PI * .25 * (summonFlip+grabFlip));
            Vector3d wrist = palmQ.transform(new Vector3d(0, 0, 2.7)).add(palms[s]);
            segment(boss.arena, arms[s][1], elbow, wrist, sign * Math.PI * (summonFlip+grabFlip));
            put(boss.arena, arms[s][2], palms[s], palmQ);
            attach(boss.arena, arms[s][3], palms[s], palmQ, new Vector3d(0, 0, 3.2), new Quaterniond());
            for (int finger = 0; finger < 4; finger++) {
                Vector3d joint = finger < 3 ? new Vector3d((finger - 1) * 1.65, 0, -2.5)
                    : new Vector3d(-sign * 2.4, -.2, .4);
                Quaterniond q = new Quaterniond(palmQ).rotateY(finger == 3 ? sign * -Math.PI / 2 : Math.PI);
                palmQ.transform(joint).add(palms[s]);
                for (int j = 0; j < 3; j++) {
                    q.rotateX(curls[s] * (j == 0 ? .65 : .95));
                    put(boss.arena, digits[s][finger][j], joint, q);
                    joint.add(q.transform(new Vector3d(0, 0, 1.85)));
                }
            }
        }
        if (f.state() == CryptFight.State.DYING && deathStart != null && t < 1.5) {
            double blend = CryptFight.smooth(t / 1.5);
            for (int i = 0; i < bones.size(); i++) {
                var bone = bones.get(i); var start = deathStart.get(i);
                bone.position.set(new Vector3d(start.position).lerp(bone.position, blend));
                bone.rotation.set(new Quaterniond(start.rotation).slerp(bone.rotation, blend));
                bone.lengthScale = start.lengthScale + (bone.lengthScale - start.lengthScale) * blend;
            }
        }
    }
    private static Quaterniond lookRotation(CryptArena arena, Vector3d skull, Vector3d worldTarget,
                                            boolean fromMouth) {
        Vector3d direction = local(arena, worldTarget);
        direction.z = -direction.z;
        direction.sub(skull);
        if (!direction.isFinite() || direction.lengthSquared() < 1e-8) return new Quaterniond();
        double yaw = Math.atan2(-direction.x, -direction.z);
        double pitch = Math.atan2(direction.y, Math.hypot(direction.x, direction.z));
        if (fromMouth) pitch += Math.asin(Math.clamp(3.7 / direction.length(), 0, 1));
        else pitch = Math.clamp(pitch, -1.2, 1.2);
        return new Quaterniond().rotationYXZ(yaw, pitch, 0);
    }
    private void segment(CryptArena arena, Bone b, Vector3d a, Vector3d c, double roll) {
        b.lengthScale = a.distance(c) / 15;
        Quaterniond q = new Quaterniond().rotationTo(new Vector3d(0, 0, 1), new Vector3d(c).sub(a).normalize());
        q.rotateZ(roll);
        put(arena, b, new Vector3d(a).lerp(c, .5), q);
    }
    private void put(CryptArena arena, Bone bone, Vector3d p, Quaterniond q) {
        bone.position.set(arena.point(p.x, p.y, -p.z)); bone.rotation.set(basis).mul(q);
    }
    private void attach(CryptArena a, Bone b, Vector3d p, Quaterniond q, Vector3d offset, Quaterniond own) {
        put(a, b, q.transform(offset).add(p), new Quaterniond(q).mul(own));
    }
    public static Vector3d local(CryptArena a, Vector3d world) {
        Vector3d base = a.point(0, 0, 0), v = new Vector3d(world).sub(base);
        return new Vector3d(v.dot(a.point(1, 0, 0).sub(base)), v.y, v.dot(a.point(0, 0, 1).sub(base)));
    }
    public Vector3d mouth() { return head.rotation.transform(new Vector3d(0, -3.7, -8)).add(head.position); }
    public Vector3d head() { return new Vector3d(head.position); }
    public Vector3d crown() { return new Vector3d(crown.position); }
    public Vector3d palm(int side) { return new Vector3d(arms[side][2].position); }
}
