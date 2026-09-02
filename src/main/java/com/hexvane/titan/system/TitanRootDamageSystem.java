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
 * Keeps the titan's own health pool out of reach.
 *
 * <p>The invisible root holds the summed health of every ore node so the boss bar has something to draw
 * from, and holding a stat map is also what makes an entity a legal attack target. Its box sits between the
 * legs, so without this a swing down there would drain the bar without breaking a single node.
 *
 * <p>The obvious fix, the engine's {@code Invulnerable} marker, is the wrong tool: it is replicated to
 * clients, and the client answers it by swapping the boss bar to its white indestructible styling. Refusing
 * the damage here leaves the root just as untouchable and the bar in its ordinary red.
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
