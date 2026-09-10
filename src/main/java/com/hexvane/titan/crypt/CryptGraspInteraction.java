package com.hexvane.titan.crypt;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;

public final class CryptGraspInteraction extends SimpleInstantInteraction {
    public static final String TYPE = "CryptGrasp";
    public static final BuilderCodec<CryptGraspInteraction> CODEC = BuilderCodec.builder(
        CryptGraspInteraction.class, CryptGraspInteraction::new, SimpleInstantInteraction.CODEC).build();
    @Override protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        CryptStaff.cast(context, true);
    }
}
