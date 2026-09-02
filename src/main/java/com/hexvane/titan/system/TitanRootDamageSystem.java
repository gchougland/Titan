package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.component.Archetype;
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
 * Cancels every damage event aimed at a titan's invisible root.
 *
 * <p>The root holds the summed health of the ore nodes for the boss bar to draw from, and any entity
 * carrying a stat map is a legal attack target. Its box sits between the legs, so a swing down there would
 * otherwise drain the bar without breaking a node.
 *
 * <p>The engine's {@code Invulnerable} marker is not used for this: it replicates to clients, which answer
 * it by swapping the boss bar to its white indestructible styling.
 */
public final class TitanRootDamageSystem extends DamageEventSystem {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanComponent.getComponentType());

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
