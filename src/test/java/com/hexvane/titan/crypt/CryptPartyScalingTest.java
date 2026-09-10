package com.hexvane.titan.crypt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CryptPartyScalingTest {
    @Test void additionalPlayersScaleCrownAndBothBraceletsAlongsideServerMultiplier() {
        for (int players=1; players<=5; players++) {
            var fight=new CryptFight(1,.8f,players);
            double expected=1+.55*(players-1);
            assertEquals(2600*.8*expected,fight.crown(),.01);
            assertEquals(340*.8*expected,fight.bracelet(0),.01);
            assertEquals(fight.bracelet(0),fight.bracelet(1));
            assertEquals(players,fight.partySize());
        }
        assertEquals(2600,new CryptFight(1,1,0).crown());
    }
    @Test void lateJoinPreservesDamagePercentagesPhaseAndBrokenBracelet() {
        var fight=awake();
        fight.hit(0,1000,1,1); fight.hit(1,10000,1,2); fight.hit(2,170,1,3);
        double crown=fight.crown()/fight.maxCrown, bracelet=fight.bracelet(1)/fight.maxBracelet;
        int phase=fight.phase(),revision=fight.revision();
        assertTrue(fight.growPartyTo(3));
        assertEquals(crown,fight.crown()/fight.maxCrown,1e-6);
        assertEquals(bracelet,fight.bracelet(1)/fight.maxBracelet,1e-6);
        assertEquals(0,fight.bracelet(0));
        assertEquals(phase,fight.phase()); assertEquals(revision,fight.revision());
        float scaledCrown=fight.crown();
        assertFalse(fight.growPartyTo(1)); assertFalse(fight.growPartyTo(3));
        assertEquals(scaledCrown,fight.crown());
    }
    @Test void lateJoinDoesNotReformBraceletsBeforeStunRecovery() {
        var fight=awake();
        fight.hit(1,10000,1,1); fight.hit(2,10000,1,2);
        advance(fight,2.3);
        assertTrue(fight.growPartyTo(4));
        assertEquals(CryptFight.State.STUNNED,fight.state());
        assertEquals(0,fight.bracelet(0)); assertEquals(0,fight.bracelet(1));
        advance(fight,12.1); advance(fight,3.1);
        assertEquals(fight.maxBracelet,fight.bracelet(0));
        assertEquals(fight.maxBracelet,fight.bracelet(1));
    }
    @Test void lateJoinCannotRescaleOrReviveADeadBoss() {
        var fight=awake(); fight.hit(0,10000,1,1);
        assertFalse(fight.growPartyTo(5)); assertEquals(0,fight.crown());
        assertEquals(1,fight.partySize()); assertEquals(CryptFight.State.DYING,fight.state());
        advance(fight,10.1); assertFalse(fight.growPartyTo(5));
        assertEquals(CryptFight.State.FINISHED,fight.state());
    }
    private static CryptFight awake() { var fight=new CryptFight(1,1); advance(fight,14.1); return fight; }
    private static void advance(CryptFight fight,double seconds) {
        for(double t=0;t<seconds;t+=.05) fight.tick(Math.min(.05,seconds-t));
    }
}
