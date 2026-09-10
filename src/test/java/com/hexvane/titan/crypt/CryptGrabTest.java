package com.hexvane.titan.crypt;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CryptGrabTest {
    @Test void pressureRequiresSustainedRecentHitsInsteadOfOneLargeHit() {
        assertFalse(CryptGrab.ready(new CryptGrab.Pressure(0,3,1,0),3));
        assertFalse(CryptGrab.ready(new CryptGrab.Pressure(0,1,10,0),1));
        assertTrue(CryptGrab.ready(new CryptGrab.Pressure(0,3,6,0),3));
        assertFalse(CryptGrab.ready(new CryptGrab.Pressure(0,3,6,0),8));
    }
    @Test void grabWaitsForRestAndCannotReplaceAStunOrDeath() {
        var fight=new CryptFight(10,1);
        assertFalse(fight.requestGrab());
        advance(fight,14.1); assertFalse(fight.requestGrab());
        advance(fight,1.2); assertTrue(fight.requestGrab());
        assertEquals(CryptFight.Move.GRAB,fight.move());
        assertFalse(fight.collidableArms());
        assertFalse(fight.requestGrab());
        fight.hit(1,10000,1,1); fight.hit(2,10000,1,2);
        assertEquals(CryptFight.State.FALLING,fight.state());
        assertFalse(fight.requestGrab());
        fight.hit(0,10000,1,3);
        assertEquals(CryptFight.State.DYING,fight.state());
        assertFalse(fight.requestGrab());
    }
    @Test void releasePointsStayOnTheRoomFloorAndAvoidTheCoffin() {
        var arena=new CryptArena(new Vector3d(30,90,40),.7f);
        for(double x:new double[]{-70,0,70}) for(double z:new double[]{-40,29,100}) {
            var point=CryptGrab.returnPoint(arena,arena.point(x,15,z));
            var local=CryptRig.local(arena,point);
            assertTrue(arena.contains(point));
            assertEquals(.35,local.y,1e-6);
            assertTrue(local.z>=18-1e-6 && local.z<=46+1e-6);
            assertTrue(Math.abs(local.x)>=7 || Math.abs(local.z-29)>=7);
        }
    }
    private void advance(CryptFight fight,double seconds) {
        for(double t=0;t<seconds;t+=.05) fight.tick(Math.min(.05,seconds-t));
    }
}
