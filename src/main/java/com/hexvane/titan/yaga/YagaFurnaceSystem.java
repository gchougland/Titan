package com.hexvane.titan.yaga;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Keeps a Baba Yaga's furnace burning.
 *
 * <p>Its own system rather than part of {@link YagaPetSystem}, which is about where the house walks and how
 * low it sits. The two share nothing but the component they read.
 *
 * <p>All it does is say when: the furnace itself decides how much time has passed and what to do with it,
 * the same way a furnace block does.
 */
public final class YagaFurnaceSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query =
        Archetype.of(YagaComponent.getComponentType(), TransformComponent.getComponentType());

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(final float dt,
                     final int index,
                     @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final var yaga = archetypeChunk.getComponent(index, YagaComponent.getComponentType());
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (yaga == null || transform == null) return;

        final YagaFurnace furnace = yaga.getFurnace();
        if (furnace == null) return;

        if (furnace.isIdle()) {
            furnace.idle(store);
            return;
        }

        // Deferred, because output that will not fit in the tray is thrown on the ground and a tick may not
        // spawn entities. The position is taken here rather than in the deferred call so that it is where
        // the house was when the furnace was run, not wherever it has walked to by the time it happens.
        final var position = new Vector3d(transform.getPosition());
        store.getExternalData().getWorld().execute(() -> furnace.tick(store, position));
    }
}
