package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Keeps ore nodes glued to their sockets as the titan moves.
 *
 * <p>Destruction is handled elsewhere: {@link TitanWeakpointDeathSystem} plays the break effect and
 * {@link TitanComponent#auditWeakpoints} decides when the titan has run out of nodes.
 */
public final class TitanWeakpointSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanWeakpointComponent.getComponentType(),
        TransformComponent.getComponentType());
    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(new SystemDependency<>(Order.AFTER, TitanAnimationSystem.class));

    @Nonnull
    private final Vector3d worldPosition = new Vector3d();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void tick(final float dt,
                     final int index,
                     @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final var weakpoint = archetypeChunk.getComponent(index, TitanWeakpointComponent.getComponentType());
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (weakpoint == null || transform == null) return;

        final Ref<EntityStore> self = archetypeChunk.getReferenceTo(index);
        final Ref<EntityStore> owner = weakpoint.getOwner();
        final TitanComponent titan = owner != null && owner.isValid()
            ? store.getComponent(owner, TitanComponent.getComponentType())
            : null;

        if (titan == null) {
            commandBuffer.removeEntity(self, RemoveReason.REMOVE);
            return;
        }

        if (titan.getState() == TitanState.DYING) {
            // The body is coming apart; take the surviving nodes with it.
            commandBuffer.removeEntity(self, RemoveReason.REMOVE);
            return;
        }

        final var pose = titan.getPose();
        if (pose == null || weakpoint.getBoneIndex() < 0 || weakpoint.getBoneIndex() >= pose.getBoneCount()) return;

        pose.transformLocal(weakpoint.getBoneIndex(), weakpoint.getLocalOffset(), worldPosition);
        transform.getPosition().set(worldPosition);
        pose.getWorldRotation(weakpoint.getBoneIndex(), weakpoint.getLocalRotation(), transform.getRotation());
    }

}
