package com.hexvane.titan.yaga;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YagaBuoyancyTest {
    @Test void bothFloorsFloatJustAboveDeepWaterAndRestingCannotSinkThem() {
        for (double height : new double[]{6.5, 14, 28}) for (double sink : new double[]{0, 3, 13}) {
            var support = YagaBuoyancy.chooseSupport(80, 120, height, sink);
            assertTrue(support.swimming());
            assertEquals(120.15, support.rootY() + height, .000001);
        }
    }
    @Test void shallowWaterAndShoreRetainNormalWalkingAndCrouching() {
        for (double water : new double[]{100,106,106.5,Double.NaN}) {
            var support = YagaBuoyancy.chooseSupport(100, water, 6.5, 2);
            assertFalse(support.swimming());
            assertEquals(98, support.rootY());
        }
        assertTrue(YagaBuoyancy.chooseSupport(100,106.51,6.5,0).swimming());
        assertFalse(YagaBuoyancy.chooseSupport(Double.NaN,120,14,0).swimming());
        assertFalse(YagaBuoyancy.chooseSupport(80,120,0,0).swimming());
    }
    @Test void lavaAndOtherFluidsAreNotWater() {
        assertTrue(YagaBuoyancy.isWater("Water"));
        assertTrue(YagaBuoyancy.isWater("Water_Source"));
        assertFalse(YagaBuoyancy.isWater("Lava_Source"));
        assertFalse(YagaBuoyancy.isWater(null));
    }
}
