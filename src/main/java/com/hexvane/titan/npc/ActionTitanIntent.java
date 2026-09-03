package com.hexvane.titan.npc;

import com.hexvane.titan.entity.TitanIntent;
import com.hexvane.titan.npc.builders.BuilderActionTitanIntent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Queues a {@link TitanIntent} on the titan linked from this brain NPC. */
public final class ActionTitanIntent extends ActionBase {

    @Nonnull
    private final TitanIntent intent;
    private final boolean requireReady;

    public ActionTitanIntent(@Nonnull final BuilderActionTitanIntent builder) {
        super(builder);
        this.intent = builder.getIntent();
        this.requireReady = builder.isRequireReady();
    }

    @Override
    public boolean canExecute(@Nonnull final Ref<EntityStore> ref,
                              @Nonnull final ExecutionSupport executionSupport,
                              @Nullable final InfoProvider sensorInfo,
                              final double dt,
                              @Nonnull final Store<EntityStore> store) {
        if (!super.canExecute(ref, executionSupport, sensorInfo, dt, store)) return false;
        final var titan = TitanNpcSupport.titanOf(ref, store);
        if (titan == null || !titan.isBrainDriven()) return false;
        if (requireReady && !TitanNpcSupport.canStartAttack(titan)) return false;
        return true;
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
        return TitanNpcSupport.request(titan, intent, sensorInfo);
    }
}
