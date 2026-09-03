package com.hexvane.titan.npc.builders;

import com.google.gson.JsonElement;
import com.hexvane.titan.entity.TitanIntent;
import com.hexvane.titan.npc.ActionTitanIntent;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

import javax.annotation.Nonnull;

/**
 * Builds a Role Action that queues a fixed {@link TitanIntent} on the linked titan.
 *
 * <p>Registered under several type names ({@code TitanWake}, {@code TitanSlam}, …) that hardcode the move.
 */
public final class BuilderActionTitanIntent extends BuilderActionBase {

    @Nonnull
    private final TitanIntent intent;
    private final boolean requireReady;

    public BuilderActionTitanIntent(@Nonnull final TitanIntent intent, final boolean requireReady) {
        this.intent = intent;
        this.requireReady = requireReady;
    }

    @Nonnull
    public TitanIntent getIntent() {
        return intent;
    }

    public boolean isRequireReady() {
        return requireReady;
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Queue titan intent " + intent;
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Writes " + intent + " onto the TitanComponent linked from this brain NPC for the titan executor to run.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Action build(@Nonnull final BuilderSupport builderSupport) {
        return new ActionTitanIntent(this);
    }

    @Nonnull
    @Override
    public Builder<Action> readConfig(@Nonnull final JsonElement data) {
        requireInstructionType(InstructionType.NPCOnlyInstructions);
        return this;
    }
}
