package com.hexvane.titan.npc;

import com.hexvane.titan.entity.TitanIntent;
import com.hexvane.titan.npc.builders.BuilderActionTitanIntent;
import com.hexvane.titan.npc.builders.BuilderActionTitanSetTarget;
import com.hypixel.hytale.server.npc.NPCPlugin;

import javax.annotation.Nonnull;

/** Registers Titan-specific NPC Role Actions used by brain Roles. */
public final class TitanNpcRegistration {

    private TitanNpcRegistration() {
    }

    public static void register(@Nonnull final NPCPlugin npc) {
        npc.registerCoreComponentType("TitanSetTarget", BuilderActionTitanSetTarget::new);
        npc.registerCoreComponentType("TitanWake",
            () -> new BuilderActionTitanIntent(TitanIntent.WAKE, false));
        npc.registerCoreComponentType("TitanChase",
            () -> new BuilderActionTitanIntent(TitanIntent.CHASE, false));
        npc.registerCoreComponentType("TitanMelee",
            () -> new BuilderActionTitanIntent(TitanIntent.MELEE, true));
        npc.registerCoreComponentType("TitanSlam",
            () -> new BuilderActionTitanIntent(TitanIntent.SLAM, true));
        npc.registerCoreComponentType("TitanPound",
            () -> new BuilderActionTitanIntent(TitanIntent.POUND, true));
        npc.registerCoreComponentType("TitanHurl",
            () -> new BuilderActionTitanIntent(TitanIntent.HURL, true));
        npc.registerCoreComponentType("TitanPlow",
            () -> new BuilderActionTitanIntent(TitanIntent.PLOW, true));
        npc.registerCoreComponentType("TitanStomp",
            () -> new BuilderActionTitanIntent(TitanIntent.STOMP, true));
    }
}
