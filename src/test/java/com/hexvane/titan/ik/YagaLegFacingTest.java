package com.hexvane.titan.ik;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YagaLegFacingTest {
    @Test void asymmetricLegsKeepTheirFacingAcrossVerticalAndTurning() {
        var localRight = new Vector3d(1, 0, 0);
        var scratch = new IkMath.Scratch();
        // Both Yaga segment shapes, including the oppositely sloped shin.
        for (var localAxis : new Vector3d[]{new Vector3d(0,-5,3), new Vector3d(0,-6,-3),
                new Vector3d(0,-3,1), new Vector3d(0,-3,-1)}) {
            for (double yaw : new double[]{0, .8, Math.PI, -2.4}) {
                var root = new Quaterniond().rotationY(yaw);
                var side = root.transform(new Vector3d(localRight));
                Quaterniond previous = null;
                for (int step = -400; step <= 400; step++) {
                    var direction = root.transform(new Vector3d(.025, -1, step / 400.0).normalize());
                    var rotation = new Quaterniond();
                    IkMath.alignAxis(rotation, localAxis, direction, localRight, side, scratch);
                    assertTrue(rotation.transform(new Vector3d(localAxis).normalize()).distance(direction) < 1e-7);
                    var expectedSide = new Vector3d();
                    IkMath.perpendicular(side, direction, expectedSide);
                    assertTrue(rotation.transform(new Vector3d(localRight)).dot(expectedSide) > .999999);
                    if (previous != null) assertTrue(Math.abs(previous.dot(rotation)) > .999,
                        "No half-turn when a leg crosses vertical");
                    previous = rotation;
                }
            }
        }
    }
}
