package com.hexvane.titan.crypt;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CryptRigTest {
    @Test void adjustedJawAndCrownFollowTheSkullThroughTrackingAndSidewaysStun() {
        for (float yaw : new float[]{0, (float) (Math.PI / 2), (float) Math.PI, (float) (-Math.PI / 2)}) {
            var boss = restingBoss(yaw);
            boss.hasLookTarget = true;
            boss.lookTarget.set(boss.arena.point(25, 2, 28));
            boss.rig.sample(boss);
            settleLook(boss);
            assertSkullAttachments(boss);
            boss.fight.hit(1, 1000, 1, 1);
            boss.fight.hit(2, 1000, 1, 1);
            advance(boss, 2.3);
            assertEquals(CryptFight.State.STUNNED, boss.fight.state());
            boss.rig.sample(boss);
            assertSkullAttachments(boss);
        }
    }

    @Test void lowerShouldersStayConnectedToElbowsWithoutMovingTheHands() {
        var boss = restingBoss(0);
        boss.rig.sample(boss);
        for (String side : new String[]{"L", "R"}) {
            int sign = side.equals("L") ? -1 : 1;
            var upper = bone(boss, side + "UpperArm");
            var forearm = bone(boss, side + "Forearm");
            Vector3d shoulder = upper.rotation.transform(new Vector3d(0, 0, -7.5 * upper.lengthScale)).add(upper.position);
            assertEquals(0, shoulder.distance(boss.arena.point(sign * 9, 9, -4)), 1e-9);
            Vector3d elbow = upper.rotation.transform(new Vector3d(0, 0, 7.5 * upper.lengthScale)).add(upper.position);
            Vector3d forearmStart = forearm.rotation.transform(new Vector3d(0, 0, -7.5 * forearm.lengthScale)).add(forearm.position);
            assertEquals(0, elbow.distance(forearmStart), 1e-9);
            assertEquals(0, bone(boss, side + "Palm").position.distance(boss.arena.point(sign * 14, 3.5, 13)), 1e-9);
        }
    }

    private static void assertSkullAttachments(CryptBossComponent boss) {
        var head = bone(boss, "Head");
        var inverse = new Quaterniond(head.rotation).conjugate();
        var jawOffset = inverse.transform(new Vector3d(bone(boss, "Jaw").position).sub(head.position));
        var crownOffset = inverse.transform(new Vector3d(bone(boss, "Crown").position).sub(head.position));
        assertEquals(0, jawOffset.distance(new Vector3d(0, -2.3, 3)), 1e-7,
            "The two-block forward adjustment belongs to the skull frame, including when it turns or falls");
        assertEquals(0, crownOffset.distance(new Vector3d(0, 2.5, 0)), 1e-7);
    }

    @Test void combatHeadFollowsTheLiveTargetSmoothlyInEveryArenaRotation() {
        for (float yaw : new float[]{0, (float) (Math.PI / 2), (float) Math.PI, (float) (-Math.PI / 2)}) {
            var boss = restingBoss(yaw);
            boss.rig.sample(boss);
            var original = new Quaterniond(bone(boss, "Head").rotation);
            boss.hasLookTarget = true;
            boss.lookTarget.set(boss.arena.point(28, 2, 24));
            boss.elapsed += .05;
            boss.rig.sample(boss);
            assertTrue(Math.abs(original.dot(bone(boss, "Head").rotation)) > .99,
                "Tracking should ease toward a newly selected player");
            settleLook(boss);
            assertFacing(boss, boss.lookTarget, .997);
            boss.lookTarget.set(boss.arena.point(-26, 2, 16));
            settleLook(boss);
            assertFacing(boss, boss.lookTarget, .997);
        }
    }

    @Test void activeBeamFacesItsDamageEndpointFromTheLoweredMouthInsteadOfThePlayer() {
        var boss = summoningBoss(CryptFight.Move.BEAM);
        boss.hasLookTarget = true;
        boss.lookTarget.set(boss.arena.point(-28, 2, 12));
        advance(boss, boss.fight.windup() + .2);
        boss.rig.sample(boss);
        for (double right : new double[]{-28, 0, 28}) {
            boss.beamAim.set(boss.arena.point(right, 1.2, 35));
            boss.elapsed += .05;
            boss.rig.sample(boss);
            Vector3d ray = new Vector3d(boss.beamAim).sub(boss.rig.mouth()).normalize();
            Vector3d facing = bone(boss, "Head").rotation.transform(new Vector3d(0, 0, -1));
            assertEquals(1, ray.dot(facing), 1e-9,
                "The rendered mouth and the beam hit test must share the same direction");
            assertEquals(8 + Math.sin(boss.elapsed * 1.4) * .24,
                boss.arena.local(boss.rig.head()).y, 1e-9);
        }
    }

    @Test void targetTrackingLeavesIntroAndFallenCrownPosesUntouched() {
        var a = restingBoss(0);
        var b = restingBoss(0);
        a.fight = new CryptFight(1, 1);
        b.fight = new CryptFight(1, 1);
        b.hasLookTarget = true;
        b.lookTarget.set(b.arena.point(32, 2, 5));
        for (int frame = 0; frame < 56; frame++) {
            a.rig.sample(a); b.rig.sample(b);
            assertSamePose(a, b);
            a.fight.tick(.25); b.fight.tick(.25);
            a.elapsed += .25; b.elapsed += .25;
        }
        for (var boss : new CryptBossComponent[]{a, b}) {
            boss.fight.hit(1, 1000, 1, 1);
            boss.fight.hit(2, 1000, 1, 1);
        }
        for (int frame = 0; frame < 20; frame++) {
            a.rig.sample(a); b.rig.sample(b);
            assertSamePose(a, b);
            a.fight.tick(.25); b.fight.tick(.25);
            a.elapsed += .25; b.elapsed += .25;
        }
    }

    @Test void sweepPalmSharesTheSelectedForwardLane() {
        for (var move : new CryptFight.Move[]{CryptFight.Move.SWEEP_LEFT, CryptFight.Move.SWEEP_RIGHT}) {
            var boss = summoningBoss(move);
            boss.sweepOffset = 17;
            advance(boss, boss.fight.windup() + boss.fight.activeDuration() * .5);
            boss.rig.sample(boss);
            double active = (boss.fight.time() - boss.fight.windup()) / boss.fight.activeDuration();
            Vector3d palm = boss.arena.local(boss.rig.palm(move == CryptFight.Move.SWEEP_LEFT ? 0 : 1));
            assertEquals(CryptAttackGeometry.sweepForward(active) + boss.sweepOffset, palm.z, 1e-9);
        }
    }

    private static CryptBossComponent restingBoss(float yaw) {
        var boss = new CryptBossComponent();
        boss.arena = new CryptArena(new Vector3d(100, 80, 100), yaw);
        boss.fight = new CryptFight(1, 1);
        boss.rig = new CryptRig(boss.arena);
        while (boss.fight.state() == CryptFight.State.INTRO) boss.fight.tick(.25);
        return boss;
    }

    private static void settleLook(CryptBossComponent boss) {
        for (int frame = 0; frame < 50; frame++) {
            boss.elapsed += .05;
            boss.rig.sample(boss);
        }
    }

    private static void assertFacing(CryptBossComponent boss, Vector3d target, double minimumDot) {
        Vector3d desired = new Vector3d(target).sub(boss.rig.head()).normalize();
        Vector3d facing = bone(boss, "Head").rotation.transform(new Vector3d(0, 0, -1));
        assertTrue(desired.dot(facing) > minimumDot);
    }

    private static void assertSamePose(CryptBossComponent a, CryptBossComponent b) {
        for (int index = 0; index < a.rig.bones.size(); index++) {
            var left = a.rig.bones.get(index); var right = b.rig.bones.get(index);
            assertEquals(0, left.position.distance(right.position), 1e-9, left.name);
            assertEquals(1, Math.abs(left.rotation.dot(right.rotation)), 1e-9, left.name);
        }
    }

    @Test void summoningRaisesWholeHandsWithNaturalFingerBendsAndConnectedWrists() {
        for (var move : new CryptFight.Move[]{CryptFight.Move.MINIONS, CryptFight.Move.BLUE_FIRE}) {
            var boss = summoningBoss(move);
            advance(boss, boss.fight.windup() + .2);
            boss.rig.sample(boss);
            for (String side : new String[]{"L", "R"}) {
                var palm = bone(boss, side + "Palm");
                var forearm = bone(boss, side + "Forearm");
                Vector3d wrist = palm.rotation.transform(new Vector3d(0, 0, 2.7)).add(palm.position);
                Vector3d armEnd = forearm.rotation.transform(new Vector3d(0, 0, 7.5 * forearm.lengthScale)).add(forearm.position);
                assertEquals(0, wrist.distance(armEnd), 1e-9, "Rotating a hand must not detach its forearm");
                for (int finger = 0; finger < 3; finger++) {
                    for (int joint = 0; joint < 3; joint++) {
                        var digit = bone(boss, side + "Finger" + finger + "_" + joint);
                        assertTrue(digit.rotation.transform(new Vector3d(0, 0, 1)).y > .95,
                            "Summoning fingers should point upward as whole articulated digits");
                        var relative = new Quaterniond(palm.rotation).conjugate().mul(digit.rotation);
                        assertTrue(relative.transform(new Vector3d(0, 0, 1)).y < 0,
                            "The joints must retain their normal closing direction in the palm frame");
                    }
                }
            }
        }
    }

    @Test void summoningTurnsWristsBeforeLiftingAndReturnsContinuouslyToRest() {
        var boss = summoningBoss(CryptFight.Move.MINIONS);
        boss.rig.sample(boss);
        var start = new Vector3d(bone(boss, "LPalm").position);
        var rotation = new Quaterniond(bone(boss, "LPalm").rotation);
        advance(boss, boss.fight.windup() * .2);
        boss.rig.sample(boss);
        assertEquals(start.y, bone(boss, "LPalm").position.y, 1e-9);
        assertTrue(Math.abs(rotation.dot(bone(boss, "LPalm").rotation)) < .99,
            "The wrist should visibly turn before the hand begins rising");
        while (boss.fight.state() == CryptFight.State.ATTACK) boss.fight.tick(.05);
        boss.rig.sample(boss);
        assertEquals(0, start.distance(bone(boss, "LPalm").position), 1e-9);
        assertEquals(1, Math.abs(rotation.dot(bone(boss, "LPalm").rotation)), 1e-9);
    }

    private static CryptBossComponent summoningBoss(CryptFight.Move move) {
        var boss = new CryptBossComponent();
        boss.arena = new CryptArena(new Vector3d(100, 80, 100));
        boss.fight = new CryptFight(1, 1);
        boss.rig = new CryptRig(boss.arena);
        while (boss.fight.state() == CryptFight.State.INTRO) boss.fight.tick(.25);
        boss.fight.hit(0, move == CryptFight.Move.BEAM ? 1900 : 900, 1, 1);
        for (int i = 0; i < 10000; i++) {
            if (boss.fight.state() == CryptFight.State.ATTACK && boss.fight.move() == move) return boss;
            boss.fight.tick(.05);
        }
        throw new AssertionError("Requested deterministic move was never selected");
    }

    private static CryptRig.Bone bone(CryptBossComponent boss, String name) {
        return boss.rig.bones.stream().filter(bone -> bone.name.equals(name)).findFirst().orElseThrow();
    }

    private static void advance(CryptBossComponent boss, double seconds) {
        for (double remaining = seconds; remaining > 1e-10; remaining -= Math.min(.05, remaining)) {
            boss.fight.tick(Math.min(.05, remaining));
        }
    }

    @Test void killingTheFallenCrownPreservesEveryBonePoseBeforeDeathMotion() {
        var boss = new CryptBossComponent();
        boss.arena = new CryptArena(new Vector3d(100, 80, 100));
        boss.fight = new CryptFight(1, 1);
        boss.rig = new CryptRig(boss.arena);
        for (int i = 0; i < 57; i++) boss.fight.tick(.25);
        boss.fight.hit(1, 1000, 1, 1);
        boss.fight.hit(2, 1000, 1, 1);
        for (int i = 0; i < 10; i++) boss.fight.tick(.25);
        assertEquals(CryptFight.State.STUNNED, boss.fight.state());
        boss.rig.sample(boss);
        var positions = boss.rig.bones.stream().map(b -> new Vector3d(b.position)).toList();
        var rotations = boss.rig.bones.stream().map(b -> new Quaterniond(b.rotation)).toList();
        var scales = boss.rig.bones.stream().map(b -> b.lengthScale).toList();
        boss.fight.hit(0, 10000, 1, 2);
        boss.rig.sample(boss);
        for (int i = 0; i < boss.rig.bones.size(); i++) {
            var bone = boss.rig.bones.get(i);
            assertEquals(0, positions.get(i).distance(bone.position), 1e-9, bone.name);
            assertEquals(1, Math.abs(rotations.get(i).dot(bone.rotation)), 1e-9, bone.name);
            assertEquals(scales.get(i), bone.lengthScale, 1e-9, bone.name);
        }
        for (int i = 0; i < 8; i++) { boss.fight.tick(.25); boss.rig.sample(boss); }
        assertTrue(boss.rig.head().distance(positions.getFirst()) > 1);
        boss.rig.bones.forEach(b -> {
            assertTrue(b.position.isFinite(), b.name);
            assertEquals(1, b.rotation.lengthSquared(), 1e-9, b.name);
        });
    }
}
