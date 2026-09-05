package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanSpawnFxComponent;
import com.hexvane.titan.entity.TitanState;
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
 * Flies a titan's blocks in from a shell around it as it appears.
 *
 * <p>Runs between {@link TitanAnimationSystem}, which decides where each bone is, and
 * {@link TitanPartSyncSystem}, which normally puts the blocks there. It computes the same destination the
 * sync system would and writes a point on the way to it instead, so the effect rides on top of whatever the
 * body is doing rather than freezing it: a house can walk while it is still assembling.
 *
 * <p>The hand-off is by the presence of {@link TitanSpawnFxComponent} — the sync system skips any part that
 * still carries one, and this drops it on arrival. Gating on a component rather than on a flag over the
 * whole titan means no coordination between the two systems and no moment where both write one transform,
 * and it lets the blocks finish one at a time, which is what staggering them is for.
 */
public final class TitanSpawnFxSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanPartComponent.getComponentType(),
        TitanSpawnFxComponent.getComponentType(),
        TransformComponent.getComponentType());

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency<>(Order.AFTER, TitanAnimationSystem.class),
        new SystemDependency<>(Order.BEFORE, TitanPartSyncSystem.class));

    /** Where one block is headed. Per thread, so the loop can still be split across the pool. */
    @Nonnull
    private static final ThreadLocal<Vector3d> TARGET = ThreadLocal.withInitial(Vector3d::new);

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
        final var fx = archetypeChunk.getComponent(index, TitanSpawnFxComponent.getComponentType());
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (part == null || fx == null || transform == null) return;

        final Ref<EntityStore> self = archetypeChunk.getReferenceTo(index);

        // Through the command buffer rather than the store, for the reason given in TitanPartSyncSystem:
        // the store's getter asserts the world thread and this can be running on a pool thread.
        final Ref<EntityStore> owner = part.getOwner();
        final TitanComponent titan = owner != null && owner.isValid()
            ? commandBuffer.getComponent(owner, TitanComponent.getComponentType())
            : null;

        if (titan == null) {
            commandBuffer.removeEntity(self, RemoveReason.REMOVE);
            return;
        }

        // Something killed it mid-assembly. Handing the block straight to the ragdoll is the only sensible
        // ending: it has nowhere left to fly to.
        if (titan.getState() == TitanState.DYING) {
            commandBuffer.removeComponent(self, TitanSpawnFxComponent.getComponentType());
            return;
        }

        final var pose = titan.getPose();
        if (pose == null || part.getBoneIndex() >= pose.getBoneCount()) return;

        final float t = fx.advance(dt);
        if (t <= 0f) return;

        if (t >= 1f) {
            // Left where the sync system will find it, so the last frame of the flight and the first frame
            // of ordinary posing agree and the block does not jump on the hand-off.
            pose.transformLocal(part.getBoneIndex(), part.getLocalOffset(), transform.getPosition());
            commandBuffer.removeComponent(self, TitanSpawnFxComponent.getComponentType());
            return;
        }

        final Vector3d target = TARGET.get();
        pose.transformLocal(part.getBoneIndex(), part.getLocalOffset(), target);

        // Eased out rather than in: the block covers most of the distance early and settles the last of it
        // slowly, which both reads as being pulled into place and leaves it near enough its destination at
        // the hand-off that the switch to ordinary posing is invisible.
        transform.getPosition().set(fx.getOrigin()).lerp(target, easeOut(t));
    }

    /** Cubic ease-out: fast away from the origin, slow into the destination. */
    private static double easeOut(final float t) {
        final double inverse = 1.0 - t;
        return 1.0 - inverse * inverse * inverse;
    }
}
