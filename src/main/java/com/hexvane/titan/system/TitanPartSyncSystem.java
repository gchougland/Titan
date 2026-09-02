package com.hexvane.titan.system;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.physics.DebrisBurst;
import com.hexvane.titan.spawn.BlockRotations;
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
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Copies bone matrices onto the entities that make up a titan's body.
 *
 * <p>There is no entity parenting in the engine, so this is what holds a titan together: a voxel's
 * transform is rewritten from the owner's pose rather than following it. When the owner starts dying the
 * parts detach here and {@link TitanRagdollSystem} takes over their motion.
 *
 * <p>Most of what follows is about not rewriting it. Every rewritten transform becomes an update in the
 * same unsplit packet, so a titan of a few thousand voxels can fill a connection with a single tick of
 * walking, which the player sees as parts of the titan flickering. Four gates therefore run before the
 * write, coarse to fine: whether the titan re-posed at all, whether this part's bone did, whether the
 * part's turn has come round if a sync interval is configured, and whether it has moved far enough to be
 * worth reporting. {@link TitanSyncStats} counts which gate stopped what, and
 * {@code /titan perf} reads it back.
 */
public final class TitanPartSyncSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Outward speed given to debris, in blocks per second per block of distance from the centre. */
    private static final double BURST_SPREAD = 0.9;
    private static final double BURST_LIFT = 4.5;
    private static final double BURST_SPIN = 3.0;

    /**
     * How often each voxel restates its size to the clients watching it. See {@code consumeScaleRefresh}.
     *
     * <p>Deliberately long, since this is only a safety net. {@code BlockEntitySystems.SendUpdates} already
     * sends the scale to every viewer in {@code newlyVisibleTo} whether or not it is marked out of date, so
     * a client walking into range of a titan is told without any help from here. The interval only covers a
     * client that somehow misses that packet. On the Roaming Temple a two-second interval cost upwards of a
     * hundred redundant size packets per tick, so it is set well above what any recovery needs.
     */
    public static final float SCALE_REFRESH_SECONDS = 30f;

    /**
     * Shortest and longest a piece of rubble lies around before it is cleaned up, in seconds. Short enough
     * that the corpse is gone by the time the loot is picked up, spread enough that it crumbles rather than
     * blinking out.
     */
    private static final float DEBRIS_LIFETIME_MIN = 3.5f;
    private static final float DEBRIS_LIFETIME_MAX = 8f;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanPartComponent.getComponentType(), TransformComponent.getComponentType());
    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(new SystemDependency<>(Order.AFTER, TitanAnimationSystem.class));

    /**
     * The intermediates one part needs to work out where it goes, one set per thread.
     *
     * <p>Held per thread rather than on the system, which would allocate nothing per part but would also
     * prevent the loop from being run in parallel.
     */
    private static final class Scratch {
        @Nonnull
        private final Vector3d worldPosition = new Vector3d();
        @Nonnull
        private final Rotation3f rotation = new Rotation3f();
        @Nonnull
        private final Quaterniond quaternion = new Quaterniond();
        @Nonnull
        private final Vector3d euler = new Vector3d();
        @Nonnull
        private final Vector3d burst = new Vector3d();
        @Nonnull
        private final Vector3d spin = new Vector3d();
    }

    @Nonnull
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

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
    public boolean isParallel(final int archetypeChunkSize, final int taskCount) {
        return TitanConfig.get().isParallelPartSync() && useParallel(archetypeChunkSize, taskCount);
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

        // Through the command buffer rather than the store: the store's getter asserts it is being called
        // on the world thread, and this system can be running on a pool thread instead.
        final Ref<EntityStore> owner = part.getOwner();
        final TitanComponent titan = owner != null && owner.isValid()
            ? commandBuffer.getComponent(owner, TitanComponent.getComponentType())
            : null;

        if (titan == null) {
            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
            return;
        }

        final var pose = titan.getPose();
        if (pose == null || part.getBoneIndex() >= pose.getBoneCount()) return;

        final Scratch scratch = SCRATCH.get();

        if (titan.getState() == TitanState.DYING) {
            detach(scratch, part, titan, transform);
            return;
        }

        // Ahead of the pose check on purpose. A titan asleep in a field never moves, and that is exactly
        // when a player wanders into view and needs to be told how big its blocks are.
        if (part.consumeScaleRefresh(dt, SCALE_REFRESH_SECONDS)) {
            final var scaleComponent = archetypeChunk.getComponent(index, EntityScaleComponent.getComponentType());
            if (scaleComponent != null) scaleComponent.setScale(part.getScale());
        }

        TitanSyncStats.countConsidered();

        // Nothing moved, so the transform already holds the right answer. This is what keeps a sleeping
        // titan cheap: it is the whole reason several of them can sit around the world at once.
        if (!titan.isPoseDirty()) {
            TitanSyncStats.countStillPose();
            return;
        }

        // Some of the titan re-posed, but not this part's bone. Everything the part's transform is built
        // from is then unchanged, so recomputing it would land on the same numbers the clients already hold.
        if (!pose.hasBoneMoved(part.getBoneIndex())) {
            TitanSyncStats.countStillBone();
            return;
        }

        final var config = TitanConfig.get();
        if (!part.consumeSyncSlot(dt, config.getPartSyncInterval())) {
            TitanSyncStats.countOffPhase();
            return;
        }

        pose.transformLocal(part.getBoneIndex(), part.getLocalOffset(), scratch.worldPosition);
        pose.getWorldRotation(part.getBoneIndex(), scratch.rotation, scratch.quaternion, scratch.euler);
        BlockRotations.compose(scratch.rotation, part.getBlockRotation(), scratch.quaternion, scratch.euler);

        // A NaN that escapes the IK solvers would be replicated to clients, where it poisons collision and
        // camera maths badly enough to hang them. Drop the frame and keep the last good transform instead;
        // the titan visibly stutters, which is a far better failure than a locked-up client.
        if (!isFinite(scratch.worldPosition) || !isFinite(scratch.rotation)) {
            LOGGER.at(Level.SEVERE).atMostEvery(10, TimeUnit.SECONDS).log(
                "Titan bone %d produced a non-finite transform (position %s, rotation %s); holding the previous pose",
                part.getBoneIndex(), scratch.worldPosition, scratch.rotation);
            return;
        }

        // Last, so a transform that was never going to be sent is not the one recorded as sent.
        if (!part.hasDriftedFrom(scratch.worldPosition, scratch.rotation,
            config.getPartSyncEpsilon(), config.getPartSyncRotationEpsilon())) {
            TitanSyncStats.countDeadband();
            return;
        }

        transform.getPosition().set(scratch.worldPosition);
        transform.getRotation().set(scratch.rotation);
        TitanSyncStats.countWritten();
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
    private static void detach(@Nonnull final Scratch scratch,
                               @Nonnull final TitanPartComponent part,
                               @Nonnull final TitanComponent titan,
                               @Nonnull final TransformComponent transform) {

        final var skeleton = titan.getSkeleton();
        final var pose = titan.getPose();
        if (skeleton == null || pose == null) return;

        final var random = ThreadLocalRandom.current();
        // Rolled per block rather than shared, so the pile of rubble crumbles away over a dozen seconds
        // instead of the whole corpse blinking out of existence on one frame.
        final float despawnAfter = random.nextFloat(DEBRIS_LIFETIME_MIN, DEBRIS_LIFETIME_MAX);

        if (!skeleton.getBones()[part.getBoneIndex()].isDetachable()) {
            // Bones flagged as fixed just drop straight down with the rest of the rubble.
            scratch.burst.set(0, 0, 0);
            scratch.spin.set(0, 0, 0);
            part.detach(scratch.burst, scratch.spin, despawnAfter);
            return;
        }

        pose.getWorldPosition(skeleton.getBodyBoneIndex(), scratch.worldPosition);
        scratch.burst.set(transform.getPosition()).sub(scratch.worldPosition);

        // Speed proportional to the distance from the body, so the extremities fly furthest.
        DebrisBurst.solve(scratch.burst, scratch.burst.length() * BURST_SPREAD, BURST_LIFT, scratch.burst);
        DebrisBurst.spin(random, BURST_SPIN, scratch.spin);

        part.detach(scratch.burst, scratch.spin, despawnAfter);
    }
}
