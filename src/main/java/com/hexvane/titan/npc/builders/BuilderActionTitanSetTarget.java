package com.hexvane.titan.npc.builders;

import com.google.gson.JsonElement;
import com.hexvane.titan.npc.ActionTitanSetTarget;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

import javax.annotation.Nonnull;

public final class BuilderActionTitanSetTarget extends BuilderActionBase {

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Set the linked titan's combat target from the sensor";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return getShortDescription();
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Action build(@Nonnull final BuilderSupport builderSupport) {
        return new ActionTitanSetTarget(this);
    }

    @Nonnull
    @Override
    public Builder<Action> readConfig(@Nonnull final JsonElement data) {
        requireInstructionType(InstructionType.NPCOnlyInstructions);
        return this;
    }
}
