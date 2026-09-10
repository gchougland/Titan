package com.hexvane.titan.ai;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TitanRollingBouldersTest {
    @Test void closeTargetsCannotMakeABoulderReverseInstantly() {
        double heading = TitanRollingBoulders.steer(0, Math.PI, .1);
        assertEquals(TitanRollingBoulders.TURN_RATE * .1, heading, 1e-9);
        assertTrue(Math.cos(heading) > .99);
    }

    @Test void steeringTakesShortestTurnAcrossTheAngleSeam() {
        double heading = Math.PI - .03;
        assertEquals(Math.PI + .03, TitanRollingBoulders.steer(heading, -Math.PI + .03, 1), 1e-9);
    }

    @Test void flatGroundIsNotAWallButTheSideOfAStoneIsSolid() {
        var center = new Vector3d(.5, TitanRollingBoulders.RADIUS, .5);
        assertFalse(TitanRollingBoulders.sphereBox(center, TitanRollingBoulders.RADIUS,
            new Vector3d(0,-1,0), new Vector3d(1,0,1)));
        assertTrue(TitanRollingBoulders.sphereBox(center, TitanRollingBoulders.RADIUS,
            new Vector3d(1,1,0), new Vector3d(2,3,1)));
        assertFalse(TitanRollingBoulders.sphereBox(center, TitanRollingBoulders.RADIUS,
            new Vector3d(3,0,0), new Vector3d(4,3,1)));
    }
}
