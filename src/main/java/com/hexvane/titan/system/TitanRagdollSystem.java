package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Integrates the debris a dead titan leaves behind.
 *
 * <p>The pieces are simulated here rather than handed to the engine's block-entity physics because they
 * need to tumble, settle into a pile and then disappear together — engine physics would keep them alive
 * indefinitely and each one is a full entity.
 */
public final class TitanRagdollSystem extends EntityTickingSystem<EntityStore> {

    private static final double GRAVITY = 26.0;
    /** Fraction of speed kept each second while airborne. */
    private static final double AIR_DRAG = 0.4;
    /** Fraction of vertical speed kept on impact. */
    private static final double RESTITUTION = 0.25;
    /** Fraction of horizontal speed kept on impact. */
    private static final double FRICTION = 0.5;
    /** Below this speed a bouncing piece is considered settled. */
    private static final double REST_SPEED = 0.8;
    private static final double TERMINAL_SPEED = 60.0;
    /** Seconds a settled piece stays before it is cleaned up. */
    private static final float DEBRIS_LIFETIME = 14f;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanPartComponent.getComponentType(), TransformComponent.getComponentType());
    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(new SystemDependency<>(Order.AFTER, TitanPartSyncSystem.class));

    @Nonnull
    private final Vector3d next = new Vector3d();

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

        final var part = archetypeChunk.getComponent(index, TitanPartComponent.getComponentType());
        if (part == null || !part.isDetached()) return;

        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (transform == null) return;

        part.addLifetime(dt);
        if (part.getLifetime() >= DEBRIS_LIFETIME) {
            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
            return;
        }
        if (part.isResting()) return;

        final var velocity = part.getVelocity();
        velocity.y -= GRAVITY * dt;
        velocity.mul(Math.pow(AIR_DRAG, dt));
        if (velocity.lengthSquared() > TERMINAL_SPEED * TERMINAL_SPEED) {
            velocity.normalize(TERMINAL_SPEED);
        }

        final var position = transform.getPosition();
        next.set(position).fma(dt, velocity);

        final var scaleComponent = archetypeChunk.getComponent(index, EntityScaleComponent.getComponentType());
        final double halfHeight = (scaleComponent == null ? 1f : scaleComponent.getScale()) * 0.5;

        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        final double ground = GroundSampler.sample(chunkStore, next.x, position.y, next.z, 2, 48);

        if (GroundSampler.isValid(ground) && next.y - halfHeight <= ground) {
            next.y = ground + halfHeight;
            if (velocity.length() < REST_SPEED) {
                velocity.set(0);
                part.getAngularVelocity().set(0);
                part.setResting(true);
            } else {
                velocity.y = Math.abs(velocity.y) * RESTITUTION;
                velocity.x *= FRICTION;
                velocity.z *= FRICTION;
                part.getAngularVelocity().mul(FRICTION);
            }
        }

        position.set(next);
        tumble(transform, part.getAngularVelocity(), dt);
    }

    private void tumble(@Nonnull final TransformComponent transform, @Nonnull final Vector3d angularVelocity, final float dt) {
        final var rotation = transform.getRotation();
        rotation.setPitch(MathUtil.wrapAngle(rotation.pitch() + (float) (angularVelocity.x * dt)));
        rotation.setYaw(MathUtil.wrapAngle(rotation.yaw() + (float) (angularVelocity.y * dt)));
        rotation.setRoll(MathUtil.wrapAngle(rotation.roll() + (float) (angularVelocity.z * dt)));
    }
}
