package com.hexvane.titan.combat;

import com.hypixel.hytale.math.shape.Box;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TitanStandingOnTest {
    @Test void cornersOfMergedPlatformsCountButPlayersBesideAndBelowDoNot() {
        var center = new Vector3d(10,20,30);
        var box = new Box(-2,-1,-2,2,1,2);
        assertTrue(TitanStandingOn.standsOnBox(new Vector3d(11.9,21,31.9),center,box));
        assertFalse(TitanStandingOn.standsOnBox(new Vector3d(12.5,21,30),center,box));
        assertFalse(TitanStandingOn.standsOnBox(new Vector3d(10,19,30),center,box));
        assertFalse(TitanStandingOn.standsOnBox(new Vector3d(10,24,30),center,box));
    }
}
