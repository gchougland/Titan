package com.hexvane.titan.dunewyrm;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Dunewyrm root is an invisible health bar holder. Hits on it must not count.
 */
public final class DunewyrmRootDamageSystem extends DamageEventSystem {

    @Nonnull
    private final Query<EntityStore> query = DunewyrmComponent.getComponentType();

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void handle(final int index,
                       @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull final Store<EntityStore> store,
                       @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull final Damage damage) {
        damage.setCancelled(true);
    }
}
