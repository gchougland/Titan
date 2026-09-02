package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
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
 * Tells a titan who just hit one of its ore nodes.
 *
 * <p>Damage never lands on the titan itself, only on the nodes bolted to it, so without this a sleeping
 * titan could be whittled down from a safe distance and never so much as open an eye. Sitting in the
 * inspect group means the hit has already been applied and cannot have been cancelled by something else by
 * the time it is reported.
 *
 * <p>Only the attacker is recorded here; {@link TitanAiSystem} decides what to do about it on its next
 * tick, so waking and target selection stay in one place.
 */
public final class TitanWeakpointDamageSystem extends DamageEventSystem {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanWeakpointComponent.getComponentType());

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
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

        if (damage.isCancelled() || damage.getAmount() <= 0f) return;

        // ProjectileSource extends EntitySource and reports the shooter rather than the arrow, so an
        // archer is blamed the same way a swordsman is.
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) return;

        final Ref<EntityStore> attacker = entitySource.getRef();
        if (!attacker.isValid()) return;

        final var weakpoint = archetypeChunk.getComponent(index, TitanWeakpointComponent.getComponentType());
        if (weakpoint == null) return;

        final Ref<EntityStore> owner = weakpoint.getOwner();
        if (owner == null || !owner.isValid()) return;

        final TitanComponent titan = commandBuffer.getComponent(owner, TitanComponent.getComponentType());
        if (titan == null) return;

        titan.reportAttacker(attacker);
    }
}
