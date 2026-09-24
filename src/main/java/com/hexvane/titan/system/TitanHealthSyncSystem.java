package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Keeps the titan root's pooled Health in sync with ore nodes for Encounter boss bar / Stat sensors.
 *
 * <p>Engagement presentation (bar + music) is owned by Encounter Manager; this only maintains the Health
 * value those systems read.
 */
public final class TitanHealthSyncSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanComponent.getComponentType(),
        TransformComponent.getComponentType(),
        EntityStatMap.getComponentType());

    private static final class Scratch {
        final List<Ref<EntityStore>> nodes = new ArrayList<>();
        float[] healths = new float[16];
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

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

        final var titan = archetypeChunk.getComponent(index, TitanComponent.getComponentType());
        final var stats = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (titan == null || stats == null || titan.getWeakpointsTotal() <= 0) return;

        // Nothing reads a pet's pooled health: it has no Encounter and no bar. An egg's shell counts as
        // weakpoints, so without this its root would be publishing a boss's health value.
        final var variant = titan.getVariant();
        if (variant != null && variant.isPet()) return;

        final var scratch = SCRATCH.get();
        try {
            titan.copyWeakpoints(scratch.nodes);
            syncPooledHealth(store, titan, stats, scratch);
        } finally {
            scratch.nodes.clear();
        }
    }

    private void syncPooledHealth(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final EntityStatMap stats,
                                  @Nonnull final Scratch scratch) {

        final var nodes = scratch.nodes;

        final int healthIndex = DefaultEntityStatTypes.getHealth();
        final float floor = Math.min(1f, titan.getTotalHealth());
        final int needed = titan.getWeakpointsStillNeeded();
        if (needed <= 0) {
            stats.setStatValue(healthIndex, floor);
            return;
        }

        if (scratch.healths.length < nodes.size()) scratch.healths = new float[nodes.size()];
        final var healths = scratch.healths;

        int found = 0;
        for (final Ref<EntityStore> node : nodes) {
            if (node == null || !node.isValid()) continue;
            final var nodeStats = store.getComponent(node, EntityStatMap.getComponentType());
            final var health = nodeStats != null ? nodeStats.get(healthIndex) : null;
            if (health != null) healths[found++] = Math.max(0f, health.get());
        }

        Arrays.sort(healths, 0, found);

        float remaining = 0f;
        for (int i = 0, count = Math.min(needed, found); i < count; i++) remaining += healths[i];

        stats.setStatValue(healthIndex, Math.max(floor, remaining));
    }
}
