package com.hexvane.titan.crypt;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CryptSoulStreamTest {
    @Test void smokeFlowConvergesAtBothEndsAndKeepsItsEmissionBudget() {
        assertEquals(15, CryptFx.SOUL_STREAM_STRANDS * CryptFx.SOUL_STREAM_SAMPLES + 1);
        var from = new Vector3d(2, 13, -4);
        var to = new Vector3d(40, -3, 8);
        var basis = basis(from, to);
        for (int strand = 0; strand < CryptFx.SOUL_STREAM_STRANDS; strand++) {
            assertEquals(0, from.distance(CryptFx.streamPoint(from, to, basis[0], basis[1], 0, 2, strand)), 1e-12);
            assertEquals(0, to.distance(CryptFx.streamPoint(from, to, basis[0], basis[1], 1, 2, strand)), 1e-12);
            var middle = CryptFx.streamPoint(from, to, basis[0], basis[1], .5, 2, strand);
            var centre = new Vector3d(from).lerp(to, .5).add(0, Math.min(3, from.distance(to)*.06), 0);
            assertEquals(1.1, middle.distance(centre), 1e-12);
        }
    }

    @Test void interleavingKeepsThePlumeEvenlyFilledAsItsSamplesTravel() {
        for (double elapsed : new double[]{0,.15,.6,1.37,8.7}) {
            double[] progress = new double[14];
            int index=0;
            for (int strand=0;strand<CryptFx.SOUL_STREAM_STRANDS;strand++)
                for (int sample=0;sample<CryptFx.SOUL_STREAM_SAMPLES;sample++)
                    progress[index++]=CryptFx.streamProgress(sample,strand,elapsed);
            java.util.Arrays.sort(progress);
            for (int i=0;i<progress.length;i++) {
                double next=i+1==progress.length ? progress[0]+1 : progress[i+1];
                assertEquals(1.0/14,next-progress[i],1e-12);
            }
        }
    }

    @Test void smokeAlwaysAdvancesTowardItsDestinationIncludingVerticalAndReversedStreams() {
        var from = new Vector3d(100, 64, -200);
        for (var offset : new Vector3d[]{new Vector3d(1, 0, 0), new Vector3d(0, 50, 0),
                new Vector3d(0, -50, 0), new Vector3d(-40, 15, 12), new Vector3d(38, -12, -7)}) {
            var to = new Vector3d(from).add(offset);
            var basis = basis(from, to);
            var axis = new Vector3d(offset).normalize();
            for (int strand = 0; strand < CryptFx.SOUL_STREAM_STRANDS; strand++) {
                var previous = new Vector3d(from);
                for (int sample = 1; sample <= 200; sample++) {
                    var current = CryptFx.streamPoint(from, to, basis[0], basis[1], sample/200.0, 4.25, strand);
                    assertTrue(new Vector3d(current).sub(previous).dot(axis) > 0,
                        "Smoke must travel forward, including death's reversed destination");
                    previous = current;
                }
            }
        }
    }

    private static Vector3d[] basis(Vector3d from, Vector3d to) {
        var direction = new Vector3d(to).sub(from).normalize();
        var right = new Vector3d(direction).cross(Math.abs(direction.y) > .96
            ? new Vector3d(1, 0, 0) : new Vector3d(0, 1, 0)).normalize();
        return new Vector3d[]{right, new Vector3d(right).cross(direction).normalize()};
    }
}
