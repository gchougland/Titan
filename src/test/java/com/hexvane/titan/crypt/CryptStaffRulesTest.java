package com.hexvane.titan.crypt;

import org.junit.jupiter.api.Test;
import org.joml.Vector3d;
import static org.junit.jupiter.api.Assertions.*;

class CryptStaffRulesTest {
    @Test void chestHeightSweepHitsStandingBodyIncludingOverlapAndFastCrossing() {
        var min=new Vector3d(-.35,0,.5);
        var max=new Vector3d(.35,1.8,1.2);
        assertEquals(.28, CryptStaffRules.bodyIntersection(new Vector3d(0,1.6,0),new Vector3d(0,1.6,1),min,max,.22),1e-8);
        assertEquals(0, CryptStaffRules.bodyIntersection(new Vector3d(0,1.6,.4),new Vector3d(0,1.6,.6),min,max,.22));
        assertTrue(Double.isFinite(CryptStaffRules.bodyIntersection(new Vector3d(0,1.6,-2),new Vector3d(0,1.6,5),min,max,.22)));
        assertEquals(Double.POSITIVE_INFINITY,CryptStaffRules.bodyIntersection(new Vector3d(2,1.6,-2),new Vector3d(2,1.6,5),min,max,.22));
    }
    @Test void closeAndMediumSoulsReachBodiesWithoutOrbitingAtNormalOrSlowTicks() {
        for(double distance:new double[]{.55,1,2,3,9,15}) for(double dt:new double[]{.016,.05,.25,.8}) for(int slot=-2;slot<=2;slot++) {
            var position=new Vector3d(slot*.06,1.6,.12);
            var velocity=new Vector3d(slot*.14,Math.abs(slot*.14)*.8,1).normalize().mul(7);
            boolean hit=false;
            for(double time=0;time<5;time+=dt) {
                var target=new Vector3d(Math.sin(time*2)*Math.min(.8,distance*.1),.9,distance);
                CryptStaffRules.steer(velocity,new Vector3d(target).sub(position),dt);
                assertTrue(velocity.isFinite());assertEquals(7,velocity.length(),1e-7);
                var next=new Vector3d(position).fma(dt,velocity);
                double contact=CryptStaffRules.bodyIntersection(position,next,new Vector3d(target).add(-.35,-.9,-.35),
                    new Vector3d(target).add(.35,.9,.35),CryptStaffRules.MISSILE_RADIUS);
                if(Double.isFinite(contact)) { hit=true;break; }
                position.set(next);
            }
            assertTrue(hit,"distance="+distance+", dt="+dt+", slot="+slot);
        }
    }
    private void advance(CryptFight fight, double seconds) {
        for (int i = 0; i < Math.ceil(seconds / .05); i++) fight.tick(.05);
    }
    @Test void homingRejectsBodyCinematicsAndBrokenBracelets() {
        var f = new CryptFight(1, 1);
        assertFalse(CryptStaffRules.canTargetPool(f, 0));
        advance(f, 14.1);
        assertFalse(CryptStaffRules.canTargetPool(f, -1));
        assertTrue(CryptStaffRules.canTargetPool(f, 0));
        assertTrue(CryptStaffRules.canTargetPool(f, 1));
        f.hit(1, 10000, 1, 1);
        assertFalse(CryptStaffRules.canTargetPool(f, 1));
        assertTrue(CryptStaffRules.canTargetPool(f, 2));
        f.hit(2, 10000, 1, 2);
        advance(f, 2.3);
        assertTrue(CryptStaffRules.canTargetPool(f, 0));
        assertFalse(CryptStaffRules.canTargetPool(f, 2));
        advance(f, 15.2);
        assertTrue(CryptStaffRules.canTargetPool(f, 1));
        f.hit(0, 10000, 1, 3);
        assertFalse(CryptStaffRules.canTargetPool(f, 0));
        assertFalse(CryptStaffRules.canTargetPool(f, 1));
    }
    @Test void giantArmHoversBeforeSlammingAndLeavesTheGroundAfterImpact() {
        for (double t=0;t<1.15;t+=.02) assertTrue(CryptStaffRules.armHeight(t)>=7);
        assertEquals(7, CryptStaffRules.armHeight(1.15), .00001);
        double previous=7;
        for (double t=1.15;t<1.48;t+=.005) {
            double height=CryptStaffRules.armHeight(t);
            assertTrue(height<=previous+.00001); assertTrue(height>=0); previous=height;
        }
        assertEquals(0, CryptStaffRules.armHeight(1.48));
        assertEquals(0, CryptStaffRules.armHeight(1.84));
        assertTrue(CryptStaffRules.armHeight(2.4)>3);
        assertEquals(10, CryptStaffRules.armHeight(2.8), .0001);
    }
}
