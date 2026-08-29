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
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Keeps ore nodes stuck to their sockets and turns their destruction into titan death.
 *
 * <p>Nodes are ordinary damageable entities, so all this has to watch for is their health reaching zero.
 */
public final class TitanWeakpointSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanWeakpointComponent.getComponentType(),
        TransformComponent.getComponentType(),
        EntityStatMap.getComponentType());
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
        final var stats = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (weakpoint == null || transform == null || stats == null) return;

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

        final var health = stats.get(DefaultEntityStatTypes.getHealth());
        if (health != null && health.get() <= 0f && weakpoint.markBroken()) {
            breakNode(commandBuffer, self, titan, transform);
            return;
        }

        final var pose = titan.getPose();
        if (pose == null || weakpoint.getBoneIndex() < 0 || weakpoint.getBoneIndex() >= pose.getBoneCount()) return;

        pose.transformLocal(weakpoint.getBoneIndex(), weakpoint.getLocalOffset(), worldPosition);
        transform.getPosition().set(worldPosition);
        pose.getWorldRotation(weakpoint.getBoneIndex(), transform.getRotation());
    }

    private void breakNode(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                           @Nonnull final Ref<EntityStore> self,
                           @Nonnull final TitanComponent titan,
                           @Nonnull final TransformComponent transform) {

        final var variant = titan.getVariant();
        final var position = transform.getPosition();

        if (variant != null) {
            final String particle = variant.getImpactParticle();
            if (particle != null && !particle.isEmpty()) {
                ParticleUtil.spawnParticleEffect(particle, position, commandBuffer);
            }
        }

        if (titan.consumeWeakpoint()) {
            titan.setState(TitanState.DYING);
            playDeathSound(commandBuffer, titan, position);
        }

        commandBuffer.removeEntity(self, RemoveReason.REMOVE);
    }

    private void playDeathSound(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final TitanComponent titan,
                                @Nonnull final Vector3d position) {
        final var variant = titan.getVariant();
        if (variant == null) return;

        final String sound = variant.getDeathSound();
        if (sound == null || sound.isEmpty()) return;

        final int soundIndex = SoundEvent.getAssetMap().getIndex(sound);
        if (soundIndex == SoundEvent.EMPTY_ID) return;

        SoundUtil.playSoundEvent3d(null, soundIndex, position.x, position.y, position.z, commandBuffer);
    }
}
