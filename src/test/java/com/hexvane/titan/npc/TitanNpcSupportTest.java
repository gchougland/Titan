package com.hexvane.titan.npc;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanIntent;
import com.hexvane.titan.entity.TitanState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TitanNpcSupportTest {
    @Test void wakeCannotBeReplacedByAnAttackBeforeWakingAnimationFinishes() {
        var titan=new TitanComponent();
        for(var state:TitanState.values()) {
            titan.setState(state);
            assertEquals(state==TitanState.SLEEPING,TitanNpcSupport.canRequest(titan,TitanIntent.WAKE),state.name());
            assertEquals(state==TitanState.IDLE || state==TitanState.CHASE,TitanNpcSupport.canStartAttack(titan),state.name());
        }
    }
    @Test void attackCooldownIsRespectedInBothReadyStates() {
        var titan=new TitanComponent();
        for(var state:new TitanState[]{TitanState.IDLE,TitanState.CHASE}) {
            titan.setState(state);titan.setAttackCooldown(1);
            assertFalse(TitanNpcSupport.canStartAttack(titan));
            titan.tickAttackCooldown(.5f);assertFalse(TitanNpcSupport.canStartAttack(titan));
            titan.tickAttackCooldown(.5f);assertTrue(TitanNpcSupport.canStartAttack(titan));
        }
    }
}
