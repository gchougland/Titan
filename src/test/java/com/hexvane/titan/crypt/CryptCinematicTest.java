package com.hexvane.titan.crypt;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.CameraKeyframe;
import com.hypixel.hytale.protocol.CameraSequenceFlags;
import com.hypixel.hytale.protocol.EasingType;
import com.hypixel.hytale.server.core.modules.camera.CameraSequenceSource;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptCinematicTest {
    private final CryptArena arena = new CryptArena(new Vector3d(103, 61, -47), .7f);
    private final Vector3d eye = arena.point(2, 3.65, 27);
    private final Rotation3f look = new Rotation3f(.2f, 1.1f, 0);

    @Test void approvedOnTimePathsRetainWaypointsDurationsAndInputReturnFlags() {
        var intro = track(false, 0);
        var death = track(true, 0);
        assertEquals(6, intro.keyframeCount());
        assertEquals(5, death.keyframeCount());
        assertEquals(13.71, duration(intro), 1e-6);
        assertEquals(9.51, duration(death), 1e-6);
        assertFlags(intro); assertFlags(death);
        var frames = frames(intro);
        assertPosition(eye, frames[0]);
        assertPosition(arena.camera(), frames[1]);
        assertPosition(arena.point(-4, 13, 42), frames[2]);
        assertPosition(arena.point(6, 16, 38), frames[3]);
        assertPosition(arena.point(3, 14, 36), frames[4]);
        assertPosition(eye, frames[5]);
        var dying = frames(death);
        assertPosition(arena.point(-12, 13, 36), dying[1]);
        assertPosition(arena.point(12, 15, 33), dying[2]);
        assertPosition(arena.point(3, 9, 39), dying[3]);
        assertEquals(arena.rewardPoint().x, dying[3].lookAtPoint.x, 1e-9);
        assertEquals(arena.rewardPoint().y, dying[3].lookAtPoint.y, 1e-9);
        assertEquals(arena.rewardPoint().z, dying[3].lookAtPoint.z, 1e-9);
        assertPosition(eye, dying[4]);
    }

    @Test void lateArrivalFinishesAtOriginalGlobalTimeIncludingSegmentBoundaries() {
        for (boolean death : new boolean[]{false, true}) {
            var full = track(death, 0);
            double total = duration(full);
            for (double elapsed = .005; elapsed < total; elapsed += .017) {
                var late = track(death, elapsed);
                assertFlags(late);
                assertEquals(total - elapsed, duration(late), 2e-6);
                for (var frame : frames(late)) {
                    assertTrue(frame.duration > 0 && Float.isFinite(frame.duration));
                    assertTrue(Double.isFinite(frame.position.x + frame.position.y + frame.position.z));
                    if (frame.look != null) assertTrue(Float.isFinite(frame.look.pitch + frame.look.yaw + frame.look.roll));
                }
                assertPosition(eye, frames(late)[late.keyframeCount() - 1]);
            }
            double boundary = 0;
            for (var frame : frames(full)) {
                boundary += frame.duration;
                assertEquals(Math.max(0, total - boundary), duration(track(death, boundary)), 2e-6);
            }
        }
    }

    @Test void partialSegmentFollowsExistingSineCurveThenKeepsLaterNativeKeyframes() {
        var original = frames(track(false, 0));
        double elapsed = 4;
        var late = frames(track(false, elapsed));
        double segmentStart = original[0].duration + (double) original[1].duration;
        double segmentEnd = segmentStart + original[2].duration;
        double time = elapsed;
        int index = 0;
        while (time < segmentEnd - 1e-6) {
            var frame = late[index++];
            time += frame.duration;
            double u = (time - segmentStart) / original[2].duration;
            double sine = (1 - Math.cos(Math.PI * u)) * .5;
            assertPosition(arena.camera().lerp(arena.point(-4, 13, 42), sine), frame, 1e-6);
            assertEquals(EasingType.Linear, frame.easing);
        }
        for (int authored = 3; authored < original.length; authored++) {
            assertEquals(original[authored].duration, late[index].duration);
            assertEquals(original[authored].easing, late[index].easing);
            assertPosition(new Vector3d(original[authored].position.x, original[authored].position.y,
                original[authored].position.z), late[index]);
            index++;
        }
        assertEquals(late.length, index);
    }

    @Test void finishedOrInvalidTimeNeverReplaysTheCinematic() {
        for (boolean death : new boolean[]{false, true}) {
            for (double elapsed : new double[]{duration(track(death, 0)), 100, Double.NaN, Double.POSITIVE_INFINITY})
                assertEquals(0, track(death, elapsed).keyframeCount());
        }
    }

    private CameraSequenceSource track(boolean death, double elapsed) {
        return CryptCinematic.timeline(arena, eye, look, death, elapsed);
    }
    private static CameraKeyframe[] frames(CameraSequenceSource source) {
        var result = new CameraKeyframe[source.keyframeCount()];
        source.writeKeyframes(0, result.length, result);
        return result;
    }
    private static double duration(CameraSequenceSource source) {
        double result = 0;
        for (var frame : frames(source)) result += frame.duration;
        return result;
    }
    private static void assertFlags(CameraSequenceSource source) {
        assertTrue((source.flags() & CameraSequenceFlags.LockInput) != 0);
        assertTrue((source.flags() & CameraSequenceFlags.ReturnToGameplayCameraOnEnd) != 0);
    }
    private static void assertPosition(Vector3d expected, CameraKeyframe frame) { assertPosition(expected, frame, 1e-9); }
    private static void assertPosition(Vector3d expected, CameraKeyframe frame, double tolerance) {
        assertEquals(expected.x, frame.position.x, tolerance);
        assertEquals(expected.y, frame.position.y, tolerance);
        assertEquals(expected.z, frame.position.z, tolerance);
    }
}
