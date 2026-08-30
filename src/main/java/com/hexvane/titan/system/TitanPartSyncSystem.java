package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanPartComponent;
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
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Copies bone matrices onto the entities that make up a titan's body.
 *
 * <p>There is no entity parenting in the engine, so this is what holds a titan together: every voxel gets
 * its transform rewritten from the owner's pose each tick. When the owner starts dying the parts detach
 * here and {@link TitanRagdollSystem} takes over their motion.
 */
public final class TitanPartSyncSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Outward speed given to debris, in blocks per second per block of distance from the centre. */
    private static final double BURST_SPREAD = 0.9;
    private static final double BURST_LIFT = 4.5;
    private static final double BURST_SPIN = 3.0;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanPartComponent.getComponentType(), TransformComponent.getComponentType());
    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(new SystemDependency<>(Order.AFTER, TitanAnimationSystem.class));

    @Nonnull
    private final Vector3d worldPosition = new Vector3d();
    @Nonnull
    private final Rotation3f scratchRotation = new Rotation3f();
    @Nonnull
    private final Vector3d burst = new Vector3d();
    @Nonnull
    private final Vector3d spin = new Vector3d();

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
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (part == null || transform == null) return;

        // Detached debris belongs to the ragdoll system and must survive the owner being removed.
        if (part.isDetached()) return;

        final Ref<EntityStore> owner = part.getOwner();
        final TitanComponent titan = owner != null && owner.isValid()
            ? store.getComponent(owner, TitanComponent.getComponentType())
            : null;

        if (titan == null) {
            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
            return;
        }

        final var pose = titan.getPose();
        if (pose == null || part.getBoneIndex() >= pose.getBoneCount()) return;

        if (titan.getState() == TitanState.DYING) {
            detach(part, titan, transform);
            return;
        }

        // Nothing moved, so the transform already holds the right answer. This is what keeps a sleeping
        // titan cheap: it is the whole reason several of them can sit around the world at once.
        if (!titan.isPoseDirty()) return;

        pose.transformLocal(part.getBoneIndex(), part.getLocalOffset(), worldPosition);
        pose.getWorldRotation(part.getBoneIndex(), scratchRotation);

        // A NaN that escapes the IK solvers would be replicated to clients, where it poisons collision and
        // camera maths badly enough to hang them. Drop the frame and keep the last good transform instead;
        // the titan visibly stutters, which is a far better failure than a locked-up client.
        if (!isFinite(worldPosition) || !isFinite(scratchRotation)) {
            LOGGER.at(Level.SEVERE).atMostEvery(10, TimeUnit.SECONDS).log(
                "Titan bone %d produced a non-finite transform (position %s, rotation %s); holding the previous pose",
                part.getBoneIndex(), worldPosition, scratchRotation);
            return;
        }

        transform.getPosition().set(worldPosition);
        transform.getRotation().set(scratchRotation);
    }

    private static boolean isFinite(@Nonnull final Vector3d v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static boolean isFinite(@Nonnull final Rotation3f r) {
        return Float.isFinite(r.pitch()) && Float.isFinite(r.yaw()) && Float.isFinite(r.roll());
    }

    /**
     * Kicks one voxel loose. Speed scales with distance from the body so the extremities fly furthest and
     * the pile reads as a collapse rather than an explosion.
     */
    private void detach(@Nonnull final TitanPartComponent part,
                        @Nonnull final TitanComponent titan,
                        @Nonnull final TransformComponent transform) {

        final var skeleton = titan.getSkeleton();
        final var pose = titan.getPose();
        if (skeleton == null || pose == null) return;

        if (!skeleton.getBones()[part.getBoneIndex()].isDetachable()) {
            // Bones flagged as fixed just drop straight down with the rest of the rubble.
            burst.set(0, 0, 0);
            spin.set(0, 0, 0);
            part.detach(burst, spin);
            return;
        }

        pose.getWorldPosition(skeleton.getBodyBoneIndex(), worldPosition);
        burst.set(transform.getPosition()).sub(worldPosition);
        final double distance = burst.length();
        if (distance < 1.0e-3) {
            burst.set(0, BURST_LIFT, 0);
        } else {
            burst.div(distance).mul(distance * BURST_SPREAD);
            burst.y = Math.max(burst.y, 0) + BURST_LIFT;
        }

        final var random = ThreadLocalRandom.current();
        spin.set(
            random.nextDouble(-BURST_SPIN, BURST_SPIN),
            random.nextDouble(-BURST_SPIN, BURST_SPIN),
            random.nextDouble(-BURST_SPIN, BURST_SPIN)
        );

        part.detach(burst, spin);
    }
}
