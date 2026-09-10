package com.hexvane.titan.crypt;

import org.junit.jupiter.api.Test;
import org.joml.Vector3d;
import static org.junit.jupiter.api.Assertions.*;

class CryptAttackGeometryTest {
    @Test void warningDisksCoverTheFullCurvedSweepDamageFootprint() {
        // Check the rim of every damaging hand disk, including between the warning samples.
        // This catches a straight warning row, sparse circles and an undersized warning radius.
        for (boolean left : new boolean[]{true, false}) {
            for (int step = 0; step <= 500; step++) {
                double progress = step / 500d;
                double x = CryptAttackGeometry.sweepRight(progress, left);
                double z = CryptAttackGeometry.sweepForward(progress);
                for (int angle = 0; angle < 128; angle++) {
                    double a = angle * Math.PI * 2 / 128;
                    double edgeX = x + Math.cos(a) * CryptAttackGeometry.SWEEP_DAMAGE_RADIUS;
                    double edgeZ = z + Math.sin(a) * CryptAttackGeometry.SWEEP_DAMAGE_RADIUS;
                    double nearest = Double.POSITIVE_INFINITY;
                    for (int marker = 0; marker <= CryptAttackGeometry.SWEEP_WARNING_SEGMENTS; marker++) {
                        double u = (double) marker / CryptAttackGeometry.SWEEP_WARNING_SEGMENTS;
                        double dx = edgeX - CryptAttackGeometry.sweepRight(u, left);
                        double dz = edgeZ - CryptAttackGeometry.sweepForward(u);
                        nearest = Math.min(nearest, dx * dx + dz * dz);
                    }
                    assertTrue(nearest <= CryptAttackGeometry.SWEEP_WARNING_RADIUS * CryptAttackGeometry.SWEEP_WARNING_RADIUS,
                        "A damaging sweep position lies outside every warning disk");
                }
            }
        }
    }

    @Test void sweepDirectionsAreMirroredAndTheHandMovesForwardAtMidpoint() {
        assertEquals(-31, CryptAttackGeometry.sweepRight(0, true));
        assertEquals(31, CryptAttackGeometry.sweepRight(1, true));
        assertEquals(29, CryptAttackGeometry.sweepForward(.5));
        for (int i = 0; i <= 100; i++) {
            assertEquals(-CryptAttackGeometry.sweepRight(i / 100d, true), CryptAttackGeometry.sweepRight(i / 100d, false), 1e-10);
        }
    }
    @Test void aLowFrameRateSweepStillHitsBetweenHandSamplesWithoutHittingOutsideItsWidth() {
        var from=new Vector3d(-10,2,20); var to=new Vector3d(10,2,20);
        assertTrue(CryptAttackGeometry.sweptHandHit(new Vector3d(0,0,20),from,to,5.8,4.2));
        assertFalse(CryptAttackGeometry.sweptHandHit(new Vector3d(0,0,26),from,to,5.8,4.2));
        assertFalse(CryptAttackGeometry.sweptHandHit(new Vector3d(0,10,20),from,to,5.8,4.2));
        assertTrue(CryptAttackGeometry.sweptHandHit(new Vector3d(-10,0,20),from,from,5.8,4.2));
    }
    @Test void sweepLaneReachesPlayersInFrontAndRearOfRoom() {
        for(double forward:new double[]{14,20,30,40,46}) {
            double lane=CryptAttackGeometry.sweepForward(.5)+CryptAttackGeometry.sweepOffset(forward);
            assertTrue(Math.abs(forward-lane)<CryptAttackGeometry.SWEEP_DAMAGE_RADIUS);
        }
    }
}
