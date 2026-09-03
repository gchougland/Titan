package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.spawn.TitanTrio;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Tears down the Encounter + brain NPC when the titan root leaves the world. */
public final class TitanTrioCleanupSystem extends HolderSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanComponent.getComponentType());

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void onEntityAdd(@Nonnull final Holder<EntityStore> holder,
                            @Nonnull final AddReason reason,
                            @Nonnull final Store<EntityStore> store) {
    }

    @Override
    public void onEntityRemoved(@Nonnull final Holder<EntityStore> holder,
                                @Nonnull final RemoveReason reason,
                                @Nonnull final Store<EntityStore> store) {
        final var titan = holder.getComponent(TitanComponent.getComponentType());
        if (titan == null || !titan.isBrainDriven()) return;
        TitanTrio.detach(store, titan);
    }
}
