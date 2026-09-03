package com.hexvane.titan.npc;

import com.hexvane.titan.npc.builders.BuilderActionTitanSetTarget;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Copies the sensor-matched entity onto the linked titan's combat target. */
public final class ActionTitanSetTarget extends ActionBase {

    public ActionTitanSetTarget(@Nonnull final BuilderActionTitanSetTarget builder) {
        super(builder);
    }

    @Override
    public boolean canExecute(@Nonnull final Ref<EntityStore> ref,
                              @Nonnull final ExecutionSupport executionSupport,
                              @Nullable final InfoProvider sensorInfo,
                              final double dt,
                              @Nonnull final Store<EntityStore> store) {
        if (!super.canExecute(ref, executionSupport, sensorInfo, dt, store)) return false;
        return TitanNpcSupport.titanOf(ref, store) != null;
    }

    @Override
    public boolean execute(@Nonnull final Ref<EntityStore> ref,
                           @Nonnull final ExecutionSupport executionSupport,
                           @Nullable final InfoProvider sensorInfo,
                           final double dt,
                           @Nonnull final Store<EntityStore> store) {
        super.execute(ref, executionSupport, sensorInfo, dt, store);
        final var titan = TitanNpcSupport.titanOf(ref, store);
        if (titan == null) return false;
        TitanNpcSupport.applyTarget(titan, sensorInfo);
        if (titan.getIntent() == com.hexvane.titan.entity.TitanIntent.NONE) {
            titan.setIntent(com.hexvane.titan.entity.TitanIntent.CHASE);
        }
        return true;
    }
}
