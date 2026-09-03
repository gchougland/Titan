package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanBrainComponent;
import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Keeps the invisible brain NPC colocated with its titan so Role sensors share the fight space.
 */
public final class TitanBrainSyncSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanBrainComponent.getComponentType(),
        TransformComponent.getComponentType());

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

        final var brain = archetypeChunk.getComponent(index, TitanBrainComponent.getComponentType());
        final var brainTransform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (brain == null || brainTransform == null) return;

        final var root = brain.getTitanRoot();
        if (root == null || !root.isValid()) return;

        final var titanTransform = store.getComponent(root, TransformComponent.getComponentType());
        final var titan = store.getComponent(root, TitanComponent.getComponentType());
        if (titanTransform == null || titan == null) return;

        brainTransform.getPosition().set(titanTransform.getPosition());
        brainTransform.getRotation().setYaw(titan.getYaw());
        // Keep the encounter colocated for membership sensors.
        final var encounter = titan.getEncounterRef();
        if (encounter != null && encounter.isValid()) {
            final var encTransform = store.getComponent(encounter, TransformComponent.getComponentType());
            if (encTransform != null) {
                encTransform.getPosition().set(titanTransform.getPosition());
            }
        }
    }
}
