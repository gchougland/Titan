package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Reacts the instant an ore weakpoint dies.
 *
 * <p>Polling the node's health does not work: the engine adds {@link DeathComponent} the moment health hits
 * zero and {@code DeathSystems.CorpseRemoval} despawns the entity on its very next tick, so a ticking system
 * can miss the zero-health window entirely and never notice the node was destroyed. Watching for
 * {@code DeathComponent} being added catches every kill exactly once, whatever dealt it.
 *
 * <p>This is also the only place a titan is told it has lost a node. Nothing else may credit a break:
 * {@link TitanComponent#auditWeakpoints} reads a missing node as the rig being torn down rather than as a
 * kill, so a death has to arrive from an actual death event to count.
 */
public final class TitanWeakpointDeathSystem extends DeathSystems.OnDeathSystem {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanWeakpointComponent.getComponentType());

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void onComponentAdded(@Nonnull final Ref<EntityStore> ref,
                                 @Nonnull final DeathComponent component,
                                 @Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final var weakpoint = store.getComponent(ref, TitanWeakpointComponent.getComponentType());
        if (weakpoint == null || !weakpoint.markBroken()) return;

        final Ref<EntityStore> owner = weakpoint.getOwner();
        final TitanComponent titan = owner != null && owner.isValid()
            ? store.getComponent(owner, TitanComponent.getComponentType())
            : null;
        if (titan == null) return;

        titan.recordWeakpointBroken(ref);

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final var variant = titan.getVariant();
        if (variant == null) return;

        final String particle = variant.getImpactParticle();
        if (particle != null && !particle.isEmpty()) {
            ParticleUtil.spawnParticleEffect(particle, transform.getPosition(), commandBuffer);
        }
    }
}
