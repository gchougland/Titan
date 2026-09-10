package com.hexvane.titan.crypt;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;

public final class CryptMissilesInteraction extends SimpleInstantInteraction {
    public static final String TYPE = "CryptMissiles";
    public static final BuilderCodec<CryptMissilesInteraction> CODEC = BuilderCodec.builder(
        CryptMissilesInteraction.class, CryptMissilesInteraction::new, SimpleInstantInteraction.CODEC).build();
    @Override public WaitForDataFrom getWaitForDataFrom() { return WaitForDataFrom.Server; }
    @Override protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        CryptStaff.cast(context, false);
    }
    @Override protected void simulateFirstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        // A clientless actor still simulates its charge; only the authoritative pass spends/spawns.
    }
}
