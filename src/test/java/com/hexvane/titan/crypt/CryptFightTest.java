package com.hexvane.titan.crypt;

import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import static org.junit.jupiter.api.Assertions.*;

class CryptFightTest {
    private void advance(CryptFight f, double seconds) { for (int i = 0; i < Math.ceil(seconds / .05); i++) f.tick(.05); }
    @Test void introBlocksDamageAndReturnsToFight() {
        var f = new CryptFight(1, 1);
        assertFalse(f.hit(0, 100, 1, 0));
        advance(f, 14.1);
        assertEquals(CryptFight.State.REST, f.state());
        assertTrue(f.hit(0, 100, 1, 1));
        assertEquals(2500, f.crown());
    }
    @Test void braceletsStunOnlyTogetherAndRegenerateWithoutHealingCrown() {
        var f = new CryptFight(2, 1); advance(f, 14.1);
        f.hit(0, 500, 1, 1);
        f.hit(1, 10000, 1, 2);
        assertNotEquals(CryptFight.State.FALLING, f.state());
        f.hit(2, 10000, 1, 3);
        assertEquals(CryptFight.State.FALLING, f.state());
        advance(f, 2.3); assertEquals(CryptFight.State.STUNNED, f.state());
        advance(f, 12.1); assertEquals(CryptFight.State.RECOVERING, f.state());
        advance(f, 3.1); assertEquals(f.maxBracelet, f.bracelet(0)); assertEquals(f.maxBracelet, f.bracelet(1));
        assertEquals(1944, f.crown()); // 500 direct damage plus 78 from each bracelet break.
    }
    @Test void onlyTheBreakingHitChipsCrownAndRepeatedBrokenHitsDoNothing() {
        var f = new CryptFight(10, 2); advance(f, 14.1);
        assertTrue(f.hit(1, 100, 1, 1));
        assertEquals(f.maxCrown, f.crown());
        assertTrue(f.hit(1, 10000, 1, 2));
        assertEquals(f.maxCrown * .97f, f.crown(), .001f);
        assertFalse(f.hit(1, 10000, 1, 2));
        assertFalse(f.hit(1, 10000, 2, 3));
        assertEquals(f.maxCrown * .97f, f.crown(), .001f);
        assertEquals(f.maxBracelet, f.bracelet(1));
    }
    @Test void regeneratedBraceletsCanChipCrownAgain() {
        var f = new CryptFight(11, 1); advance(f, 14.1);
        f.hit(1, 10000, 1, 1); f.hit(2, 10000, 1, 2);
        assertEquals(2444, f.crown());
        advance(f, 2.3); advance(f, 12.1); advance(f, 3.1);
        assertEquals(f.maxBracelet, f.bracelet(0));
        assertEquals(f.maxBracelet, f.bracelet(1));
        assertTrue(f.hit(1, 10000, 1, 3));
        assertEquals(2366, f.crown());
        assertTrue(f.hit(2, 10000, 1, 4));
        assertEquals(2288, f.crown());
        assertEquals(CryptFight.State.FALLING, f.state());
    }
    @Test void braceletChipsCrossBothCrownPhaseThresholds() {
        var f = new CryptFight(12, 1); advance(f, 14.1);
        f.hit(0, f.maxCrown * .33f, 1, 1);
        assertEquals(1, f.phase());
        f.hit(1, 10000, 1, 2);
        assertEquals(2, f.phase());
        f.hit(0, f.crown() - f.maxCrown * .35f, 1, 3);
        assertEquals(2, f.phase());
        f.hit(2, 10000, 1, 4);
        assertEquals(3, f.phase());
        assertEquals(CryptFight.State.FALLING, f.state());
    }
    @Test void lethalSecondBraceletEntersDeathInsteadOfOverwritingItWithStun() {
        var f = new CryptFight(13, 1); advance(f, 14.1);
        f.hit(0, f.maxCrown - 104, 1, 1);
        assertTrue(f.hit(1, 10000, 1, 2));
        assertEquals(26, f.crown());
        assertTrue(f.hit(2, 10000, 1, 3));
        assertEquals(0, f.bracelet(0)); assertEquals(0, f.bracelet(1));
        assertEquals(0, f.crown());
        assertEquals(CryptFight.State.DYING, f.state());
        assertFalse(f.hit(0, 100, 1, 4));
        advance(f, 10.1);
        assertEquals(CryptFight.State.FINISHED, f.state());
    }
    @Test void areaSwingChargesOnePoolOncePerAttackerTick() {
        var f = new CryptFight(3, 1); advance(f, 14.1);
        assertTrue(f.hit(1, 30, 42, 99));
        for (int i = 0; i < 50; i++) assertFalse(f.hit(1, 30, 42, 99));
        assertEquals(310, f.bracelet(0));
        assertTrue(f.hit(2, 30, 42, 99));
        assertTrue(f.hit(1, 30, 43, 99));
        assertTrue(f.hit(1, 30, 42, 100));
    }
    @Test void phasesUseCrownAndDeathRunsOnce() {
        var f = new CryptFight(4, 1); advance(f, 14.1);
        f.hit(1, 10000, 1, 1); assertEquals(1, f.phase());
        f.hit(0, 900, 1, 2); assertEquals(2, f.phase());
        f.hit(0, 850, 1, 3); assertEquals(3, f.phase());
        f.hit(0, 10000, 1, 4); assertEquals(CryptFight.State.DYING, f.state());
        assertFalse(f.hit(0, 100, 1, 5));
        advance(f, 10.1); assertEquals(CryptFight.State.FINISHED, f.state());
        advance(f, 20); assertEquals(CryptFight.State.FINISHED, f.state());
    }
    @Test void firstPhaseNeverSelectsLateAttacksAndEveryMoveHasDodgeTime() {
        var f = new CryptFight(8, 1); advance(f, 14.1);
        var seen = EnumSet.noneOf(CryptFight.Move.class);
        for (int i = 0; i < 10000; i++) {
            f.tick(.1);
            if (f.state() != CryptFight.State.ATTACK) continue;
            seen.add(f.move()); assertTrue(f.windup() >= 1.6);
            assertFalse(f.move() == CryptFight.Move.BEAM || f.move() == CryptFight.Move.POISON || f.move() == CryptFight.Move.BLUE_FIRE);
        }
        assertEquals(4, seen.size());
    }
    @Test void lagCannotSkipTheIntro() {
        var f = new CryptFight(0, 1); f.tick(600); assertEquals(.25, f.time());
        f.tick(Double.NaN); f.tick(-1); assertEquals(.25, f.time());
        assertFalse(f.hit(0, Float.NaN, 0, 1));
    }
}
